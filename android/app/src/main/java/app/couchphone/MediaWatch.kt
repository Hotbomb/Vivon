package app.couchphone

import android.service.notification.NotificationListenerService

/**
 * Lets Couch Phone see the phone's media sessions (Android only shows them to notification
 * listeners; this one is allowed with root and reads no notifications). Used to pause exactly the
 * browser's video when leaving it, never Spotify playing in the background (Tv.pauseBrowser).
 */
class MediaWatch : NotificationListenerService()
