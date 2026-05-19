package life.pilot.partner.testapp

import life.pilot.partner.sdk.PartnerEnvironment
import life.pilot.partner.sdk.PilotPartnerClient
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Process-wide single instance, mirroring the integration guide's
 * "reuse one PilotPartnerClient per app process" advice.
 *
 * Secrets come from [BuildConfig], which is populated at build time from
 * (in order) `-P` Gradle properties → env vars → `local.properties`. See
 * `app/build.gradle.kts` and the integration guide's "Configuring secrets"
 * section. We do **not** call `System.getenv(...)` here — on Android that
 * reads the zygote-launched process environment, which never contains
 * developer-supplied env vars.
 */
object PartnerClientHolder {
    val client: PilotPartnerClient by lazy {
        val env = runCatching { PartnerEnvironment.valueOf(BuildConfig.PILOT_ENVIRONMENT) }
            .getOrDefault(PartnerEnvironment.SANDBOX)

        PilotPartnerClient.builder()
            .apiKey(BuildConfig.PILOT_API_KEY.ifBlank { "missing-PILOT_API_KEY" })
            .organizationUuid(BuildConfig.PILOT_ORG_UUID.ifBlank { "missing-PILOT_ORG_UUID" })
            .gatewaySecret(BuildConfig.PILOT_GATEWAY_SECRET.takeIf { it.isNotBlank() })
            .environment(env)
            .logging(HttpLoggingInterceptor.Level.BASIC)
            .build()
    }
}
