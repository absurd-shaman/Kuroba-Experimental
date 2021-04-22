package com.github.k1rakishou.chan.features.posting

import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.data.descriptor.SiteDescriptor

data class MainNotificationInfo(val activeRepliesCount: Int)

data class ChildNotificationInfo(
  val chanDescriptor: ChanDescriptor,
  val status: Status
) {

  val canCancel: Boolean
    get() {
      return when (status) {
        is Status.Uploading,
        is Status.WaitingForSiteRateLimitToPass,
        is Status.WaitingForAdditionalService,
        is Status.Preparing,-> true
        is Status.Error,
        is Status.Posted,
        Status.Canceled -> false
      }
    }

  val isOngoing: Boolean
    get() {
      return when (status) {
        is Status.Uploading,
        is Status.WaitingForSiteRateLimitToPass,
        is Status.WaitingForAdditionalService -> true
        is Status.Error,
        is Status.Preparing,
        is Status.Posted,
        Status.Canceled -> false
      }
    }

  sealed class Status(
    val statusText: String
  ) {
    class Preparing(
      chanDescriptor: ChanDescriptor
    ) : Status("Preparing to send a reply to ${chanDescriptor.userReadableString()}")

    data class WaitingForSiteRateLimitToPass(
      val remainingWaitTimeMs: Long,
      val siteDescriptor: SiteDescriptor,
    ) : Status("Waiting ${remainingWaitTimeMs}ms until ${siteDescriptor.siteName} rate limit is over")

    data class WaitingForAdditionalService(
      val availableAttempts: Int,
      val serviceName: String
    ) : Status("Waiting for external service: \"${serviceName}\" ($availableAttempts)")

    data class Uploading(
      val totalProgress: Float
    ) : Status("Uploading ${(totalProgress * 100f).toInt()}%...")

    data class Posted(
      val chanDescriptor: ChanDescriptor
    ) : Status("Posted in ${chanDescriptor.userReadableString()}")

    data class Error(
      val errorMessage: String
    ) : Status("Failed to post, error=${errorMessage}")

    object Canceled : Status("Canceled")
  }

  companion object {
    fun default(chanDescriptor: ChanDescriptor): ChildNotificationInfo {
      return ChildNotificationInfo(chanDescriptor, Status.Preparing(chanDescriptor))
    }
  }
}