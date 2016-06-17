package controllers.massivedecks.lobby

import javax.inject.Inject

import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

import controllers.massivedecks.cardcast.CardcastAPI
import controllers.massivedecks.exceptions.{BadRequestException, ForbiddenException, RequestFailedException}
import controllers.massivedecks.notifications.Notifiers
import models.massivedecks.{Game => GameModel}
import models.massivedecks.Game.Formatters._
import models.massivedecks.{Lobby => LobbyModel}
import models.massivedecks.Lobby.Formatters._
import models.massivedecks.Player
import models.massivedecks.Player.Id
import play.api.libs.iteratee.{Enumerator, Iteratee}
import play.api.libs.json.{JsValue, Json}

object Lobby {

  /**
    * Factory for dependency injection.
    */
  class LobbyFactory @Inject() (cardcast: CardcastAPI) (implicit context: ExecutionContext) {
    def build(gameCode: String) = new Lobby(cardcast, gameCode)
  }

  /**
    * How long to wait after a player disconnects to allow for them to reconnect without treating them as disconnected.
    */
  val disconnectGracePeriod: FiniteDuration = 5.seconds
  /**
    * How long to wait for calls to Cardcast to complete.
    */
  val cardCastWaitPeriod: FiniteDuration = 10.seconds

  /**
    * Wait for the given amount of time.
    * @param duration The time to wait for.
    * @return A future to wait on.
    */
  def wait(duration: FiniteDuration): Try[Future[Nothing]] = Try(Await.ready(Promise().future, duration))
}
/**
  * Represents a game lobby.
  * @param cardcast The cardcast api.
  * @param gameCode The game code for the lobby.
  */
class Lobby(cardcast: CardcastAPI, gameCode: String)(implicit context: ExecutionContext) {

  /**
    * The game in progress if there is one.
    */
  var game: Option[Game] = None

  /**
    * Notifiers for the lobby.
    */
  val notifiers: Notifiers = new Notifiers()

  /**
    * Configuration for the lobby.
    */
  var config = new Config(notifiers)

  /**
    * The players in the lobby.
    */
  val players: Players = new Players(notifiers)

  /**
    * @return The model for the lobby.
    */
  def lobby = LobbyModel.Lobby(gameCode, config.config, players.players, game.map(game => game.round))

  /**
    * Add a new player to the lobby.
    * @param name The name for the player.
    * @return The secret for the player.
    * @throws BadRequestException with key "name-in-use" if there is a player in the lobby with the same name.
    */
  def newPlayer(name: String): Player.Secret = {
    val secret = players.addPlayer(name)
    if (game.isDefined) {
      game.get.addPlayer(secret.id)
    }
    setPlayerDisconnectedAfterGracePeriod(secret.id)
    secret
  }

  /**
    * Try to add the deck to the lobby.
    * @param secret The secret for the player making the request.
    * @param playCode The cardcast play code for the deck.
    * @return An empty response.
    * @throws ForbiddenException with key "secret-wrong-or-not-a-player" if the secret is invalid.
    * @throws RequestFailedException with the key "cardcast-timeout" if the request to cardcast doesn't complete.
    */
  def addDeck(secret: Player.Secret, playCode: String): JsValue = {
    players.validateSecret(secret)
    Try(Await.ready({
      cardcast.deck(playCode).map { deck =>
        config.addDeck(deck)
      }
    }, Lobby.cardCastWaitPeriod)) match {
      case Success(result) =>
        result.value.get.get
      case Failure(exception) =>
        throw RequestFailedException.json("cardcast-timeout")
    }
    Json.toJson("")
  }

  /**
    * Add a new ai to the lobby.
    * @param secret The secret of the player making the request.
    * @throws ForbiddenException with key "secret-wrong-or-not-a-player" if the secret is invalid.
    */
  def newAi(secret: Player.Secret): Unit = {
    players.validateSecret(secret)
    players.addAi()
  }

  /**
    * Start a new game in the lobby.
    * @param secret The secret of the player making the request.
    * @return The hand of the player making the request in the new game.
    * @throws ForbiddenException with key "secret-wrong-or-not-a-player" if the secret is invalid.
    * @throws BadRequestException with the key "game-in-progress" if the game has already started.
    */
  def newGame(secret: Player.Secret): JsValue = {
    players.validateSecret(secret)
    if (game.isDefined) {
      throw BadRequestException.json("game-in-progress")
    }
    notifiers.gameStart()
    val current = new Game(players, config, notifiers)
    game = Some(current)
    Json.toJson(getHand(secret))
  }

  /**
    * Play the given cards into the round.
    * @param secret The secret of the player making the request.
    * @param cardIds The ids of the responses to play.
    * @return The hand of the player making the request after the cards have been played and replacements drawn.
    * @throws ForbiddenException with key "secret-wrong-or-not-a-player" if the secret is invalid.
    * @throws BadRequestException with key "no-game-in-progress" if there is not a game underway.
    * @throws BadRequestException with key "not-in-round" if the player is not in the round.
    * @throws BadRequestException with key "already-played" if the player has already played into the round.
    * @throws BadRequestException with key "already-judging" if the round is already in it's judging state.
    * @throws BadRequestException with key "wrong-number-of-cards-played" if the wrong number of responses were played.
    *                             The value "got" is the number of cards played, the value "expected" is the number
    *                             required for the request to succeed
    * @throws BadRequestException with key "invalid-card-id-given" if any of the card ids are not in the given player's
    *                             hand.
    */
  def play(secret: Player.Secret, cardIds: List[String]): JsValue = {
    players.validateSecret(secret)
    validateInGame().play(secret.id, cardIds)
    Json.toJson(getHand(secret))
  }

  /**
    * Choose the winning play for the round.
    * @param secret The secret of the player making the request.
    * @param winner The index of the played responses being chosen.
    * @return An empty response.
    * @throws ForbiddenException with key "secret-wrong-or-not-a-player" if the secret is invalid.
    * @throws BadRequestException with key "no-game-in-progress" if there is not a game underway.
    * @throws BadRequestException with key "not-czar" if the current player is not the czar.
    * @throws BadRequestException with key "not-judging" if the round is not yet in the judging phase.
    * @throws BadRequestException with key "no-such-played-cards" if the index does not exist.
    * @throws BadRequestException with key "already-judged" if the round is already finished.
    */
  def choose(secret: Player.Secret, winner: Int): JsValue = {
    players.validateSecret(secret)
    val game = validateInGame()
    game.choose(secret.id, winner)
    beginRound()
    Json.toJson("")
  }

  /**
    * Get the hand of the player.
    * @param secret The secret of the player making the request.
    * @return The hand of the player.
    * @throws ForbiddenException with key "secret-wrong-or-not-a-player" if the secret is invalid.
    * @throws BadRequestException with key "no-game-in-progress" if there is not a game underway.
    */
  def getHand(secret: Player.Secret): GameModel.Hand = {
    players.validateSecret(secret)
    validateInGame().getHand(secret.id)
  }

  /**
    * @return The history of the current game.
    * @throws BadRequestException with key "no-game-in-progress" if there is not a game underway.
    */
  def gameHistory() : List[GameModel.FinishedRound] = {
    validateInGame().history
  }

  /**
    * Get the lobby and hand models for the lobby.
    * @param secret The secret of the player making the request.
    * @return The lobby and the hand of the player.
    * @throws ForbiddenException with key "secret-wrong-or-not-a-player" if the secret is invalid.
    */
  def getLobbyAndHand(secret: Player.Secret): JsValue = {
    players.validateSecret(secret)
    Json.toJson(lobbyAndHand(secret))
  }

  private def lobbyAndHand(secret: Player.Secret): LobbyModel.LobbyAndHand = {
    val hand = game match {
      case Some(_) =>
        getHand(secret)
      case None =>
        GameModel.Hand(List())
    }
    LobbyModel.LobbyAndHand(lobby, hand)
  }

  /**
    * Mark the given player as having left the game.
    * @param secret The secret of the player making the request.
    * @throws ForbiddenException with key "secret-wrong-or-not-a-player" if the secret is invalid.
    */
  def leave(secret: Player.Secret): Unit = {
    players.validateSecret(secret)
    players.leave(secret.id)
    game.foreach { current =>
      if (players.activePlayers.length < Players.minimum) {
        endGame()
      }
      current.playerLeft(secret.id)
    }
  }

  private def endGame(): Unit = {
    game = None
    players.updatePlayers(players.setPlayerStatus(Player.Neutral))
    notifiers.gameEnd()
  }

  /**
    * Start skipping the given players.
    * @param secret The secret of the player making the request.
    * @param playerIds The players to start skipping.
    * @throws ForbiddenException with key "secret-wrong-or-not-a-player" if the secret is invalid.
    * @throws BadRequestException with key "no-game-in-progress" if there is not a game underway.
    * @throws BadRequestException with key "not-enough-players-to-skip" if the game would end by skipping the given
    *                             players.
    * @throws BadRequestException with key "players-must-be-skippable" if the players are not skippable.
    * @return An empty response.
    */
  def skip(secret: Player.Secret, playerIds: Set[Player.Id]): JsValue = {
    players.validateSecret(secret)
    BadRequestException.verify((players.activePlayers.length - playerIds.size) >= Players.minimum, "not-enough-players-to-skip")
    BadRequestException.verify(players.players.filter(player => playerIds.contains(player.id)).forall(player => player.disconnected), "players-must-be-skippable")
    val game = validateInGame()
    game.skip(secret.id, playerIds)
    Json.toJson("")
  }

  /**
    * Mark the given player as back into the game.
    * @param secret The secret of the player making the request.
    * @throws ForbiddenException with key "secret-wrong-or-not-a-player" if the secret is invalid.
    * @throws BadRequestException with key "not-being-skipped" if the player was not being skipped.
    * @return An empty response.
    */
  def back(secret: Player.Secret): JsValue = {
    players.validateSecret(secret)
    players.back(secret.id)
    Json.toJson("")
  }

  /**
    * Redraw the hand of the given player.
    * @param secret The secret of the player making the request.
    * @throws ForbiddenException with key "secret-wrong-or-not-a-player" if the secret is invalid.
    * @throws BadRequestException with key "rule-not-enabled" if the rule is not enabled.
    * @throws BadRequestException with key "no-game-in-progress" if there is not a game underway.
    * @throws BadRequestException with the key "not-enough-points-to-redraw" if the player doesn't have enough points.
    * @return The hand of the player.
    */
  def redraw(secret: Player.Secret): JsValue = {
    players.validateSecret(secret)
    BadRequestException.verify(config.houseRules.contains("reboot"), "rule-not-enabled")
    validateInGame().redraw(secret.id)
    Json.toJson(getHand(secret))
  }

  /**
    * Enable the given rule.
    * @param secret The secret of the player making the request.
    * @param rule The rule to enable.
    * @return An empty response.
    */
  def enableRule(secret: Player.Secret, rule: String): JsValue = {
    players.validateSecret(secret)
    config.addHouseRule(rule)
    Json.toJson("")
  }

  /**
    * Disable the given rule.
    * @param secret The secret of the player making the request.
    * @param rule The rule to disable.
    * @return An empty response.
    */
  def disableRule(secret: Player.Secret, rule: String): JsValue = {
    players.validateSecret(secret)
    config.removeHouseRule(rule)
    Json.toJson("")
  }

  /**
    * Register a websocket connection.
    * @return The iteratee and enumerator for the websocket.
    */
  def register(): (Iteratee[String, Unit], Enumerator[String]) = {
    notifiers.openedSocket(register, unregister)
  }

  private def register(secret: Player.Secret): LobbyModel.LobbyAndHand = {
    players.validateSecret(secret)
    players.register(secret.id)
    lobbyAndHand(secret)
  }

  private def unregister(playerId: Player.Id): Unit = {
    players.unregister(playerId)
    setPlayerDisconnectedAfterGracePeriod(playerId)
  }

  private def setPlayerDisconnectedAfterGracePeriod(playerId: Id): Unit = {
    Future {
      Lobby.wait(Lobby.disconnectGracePeriod)
      if (!players.connected.contains(playerId)) {
        players.updatePlayer(playerId, players.setPlayerDisconnected(true))
      }
    }
  }

  private def beginRound(): Unit = {
    val game = validateInGame()
    game.beginRound()
  }

  private def validateInGame(): Game = game match {
    case Some(state) => state
    case None => throw BadRequestException.json("no-game-in-progress")
  }

}
