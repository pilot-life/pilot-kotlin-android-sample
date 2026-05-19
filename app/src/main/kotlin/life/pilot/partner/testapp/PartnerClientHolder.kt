package life.pilot.partner.testapp

import life.pilot.partner.sdk.PartnerEnvironment
import life.pilot.partner.sdk.PilotPartnerClient
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Process-wide single instance, mirroring the integration guide's advice
 * ("Reuse one PilotPartnerClient per app process").
 *
 * In a real partner app these come from BuildConfig / secrets storage.
 * Substitute real values via gradle.properties or env before running.
 */
object PartnerClientHolder {
    val client: PilotPartnerClient by lazy {
        PilotPartnerClient.builder()
            .apiKey(System.getenv("PILOT_API_KEY") ?: "test-key")
            .organizationUuid(System.getenv("PILOT_ORG_UUID") ?: "00000000-0000-0000-0000-000000000000")
            .environment(PartnerEnvironment.SANDBOX)
            .logging(HttpLoggingInterceptor.Level.BASIC)
            .build()
    }
}
