package com.hunorkovacs.koauth.service.provider

import com.hunorkovacs.koauth.domain.OauthParams._
import com.hunorkovacs.koauth.domain._
import com.hunorkovacs.koauth.service.Arithmetics._
import com.hunorkovacs.koauth.service.provider.persistence.Persistence

import scala.concurrent.Future.successful
import scala.concurrent.{ExecutionContext, Future}

trait Verifier {

  def verifyForRequestToken(request: KoauthRequest): Future[Verification]

  def verifyForAccessToken(request: KoauthRequest): Future[Verification]

  def verifyForOauthenticate(request: KoauthRequest): Future[Verification]
}

protected class CustomVerifier(private val persistence: Persistence,
                               private val ec: ExecutionContext) extends Verifier {

  implicit private val implicitEc = ec
  import VerifierObject._

  def verifyForRequestToken(request: KoauthRequest): Future[Verification] = {
    Future(verifyRequiredParams(request, RequestTokenRequiredParams)) flatMap {
      case nok: VerificationNok => successful(nok)
      case VerificationOk =>
        persistence.getConsumerSecret(request.oauthParamsMap(ConsumerKeyName)) flatMap {
          case None => successful(VerificationFailed(MessageInvalidConsumerKey))
          case Some(consumerSecret) => fourVerifications(request, consumerSecret, "", "")
        }
    }
  }

  def verifyForAccessToken(request: KoauthRequest) =
    verifyWithToken(request, AccessTokenRequiredParams, persistence.getRequestTokenSecret)

  def verifyForOauthenticate(request: KoauthRequest) =
    verifyWithToken(request, OauthenticateRequiredParams, persistence.getAccessTokenSecret)

  def verifyWithToken(request: KoauthRequest,
                      requiredParams: List[String],
                      getSecret: (String, String) => Future[Option[String]]): Future[Verification] = {
    Future(verifyRequiredParams(request, requiredParams)) flatMap {
      case nok: VerificationNok => successful(nok)
      case VerificationOk =>
        val consumerKeyF = Future(request.oauthParamsMap(ConsumerKeyName))
        consumerKeyF flatMap { consumerKey =>
          persistence.getConsumerSecret(consumerKey) flatMap {
            case None => successful(VerificationFailed(MessageInvalidConsumerKey))
            case Some(someConsumerSecret) =>
              Future(request.oauthParamsMap(TokenName)) flatMap { token =>
                getSecret(consumerKey, token) flatMap {
                  case None => successful(VerificationFailed(MessageInvalidToken))
                  case Some(someTokenSecret) => fourVerifications(request, someConsumerSecret, token, someTokenSecret)
                }
              }
          }
        }
    }
  }

  def fourVerifications(request: KoauthRequest, consumerSecret: String, token: String, tokenSecret: String): Future[Verification] = {
    verifyNonce(request, token) flatMap { nonceVerification =>
      Future {
        List(verifySignature(request, consumerSecret, tokenSecret),
          verifyAlgorithm(request),
          verifyTimestamp(request))
          .::(nonceVerification)
          .collectFirst({ case nok: VerificationNok => nok})
          .getOrElse(VerificationOk)
      }
    }
  }

  def verifySignature(request: KoauthRequest, consumerSecret: String, tokenSecret: String): Verification = {
    val signatureBase = concatItemsForSignature(request)
    val computedSignature = sign(signatureBase, consumerSecret, tokenSecret)
    val sentSignature = request.oauthParamsMap(SignatureName)
    if (sentSignature.equals(computedSignature)) VerificationOk
    else VerificationFailed(MessageInvalidSignature + signatureBase)
  }

  def verifyNonce(request: KoauthRequest, token: String): Future[Verification] = {
    Future {
      val nonce = request.oauthParamsMap(NonceName)
      val consumerKey = request.oauthParamsMap(ConsumerKeyName)
      (nonce, consumerKey)
    } flatMap { args =>
      val (nonce, consumerKey) = args
      persistence.nonceExists(nonce, consumerKey, token) map { exists =>
        if (exists) VerificationFailed(MessageInvalidNonce)
        else VerificationOk
      }
    }
  }

  def verifyTimestamp(request: KoauthRequest): Verification = {
    val timestamp = request.oauthParamsMap(TimestampName)
    try {
      val actualStamp = timestamp.toLong
      val expectedStamp = System.currentTimeMillis() / 1000
      if (Math.abs(actualStamp - expectedStamp) <= TimePrecisionSeconds) VerificationOk
      else VerificationFailed(MessageInvalidTimestamp)
    } catch {
      case nfEx: NumberFormatException => VerificationUnsupported("Invalid timestamp format.")
    }
  }

  def verifyAlgorithm(request: KoauthRequest): Verification = {
    val signatureMethod = request.oauthParamsMap(SignatureMethodName)
    if (HmacReadable != signatureMethod) VerificationUnsupported(MessageUnsupportedMethod)
    else VerificationOk
  }

  def verifyRequiredParams(request: KoauthRequest, requiredParams: List[String]): Verification = {
    val paramsKeys = request.oauthParamsList.map(e => e._1)
    if (requiredParams.equals(paramsKeys.sorted)) VerificationOk
    else VerificationUnsupported(MessageParameterMissing +
      (paramsKeys.diff(requiredParams) ::: requiredParams.diff(paramsKeys)).mkString(", "))
  }
}

protected object VerifierObject {

  val HmacReadable = "HMAC-SHA1"
  val TimePrecisionSeconds = 10 * 60

  final val RequestTokenRequiredParams = List[String](ConsumerKeyName, SignatureMethodName, SignatureName,
    TimestampName, NonceName, VersionName, CallbackName).sorted
  final val AccessTokenRequiredParams = List[String](ConsumerKeyName, TokenName, SignatureMethodName,
    SignatureName, TimestampName, NonceName, VersionName, VerifierName).sorted
  final val OauthenticateRequiredParams = List[String](ConsumerKeyName, TokenName, SignatureMethodName,
    SignatureName, TimestampName, NonceName, VersionName).sorted

  val MessageInvalidConsumerKey = "Consumer Key does not exist."
  val MessageInvalidToken = "Token with Consumer Key does not exist."
  val MessageInvalidSignature = "Signature does not match. Signature base: "
  val MessageInvalidNonce = "Nonce was already used."
  val MessageInvalidTimestamp = "Timestamp falls outside the tolerated interval."
  val MessageUnsupportedMethod = "Unsupported Signature Method."
  val MessageParameterMissing = "OAuth parameter is missing, or duplicated. Difference: "
  val MessageNotAuthorized = "Request Token not authorized."
  val MessageUserInexistent = "User does not exist for given Consumer Key and Access Token."
}
