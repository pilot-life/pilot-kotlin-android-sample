package life.pilot.partner.testapp

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlinx.coroutines.test.runTest
import life.pilot.partner.sdk.PartnerEnvironment
import life.pilot.partner.sdk.PilotPartnerClient
import life.pilot.partner.sdk.auth.IdempotencyKey
import life.pilot.partner.sdk.error.PartnerException
import life.pilot.partner.sdk.model.CheckoutPatron
import life.pilot.partner.sdk.model.CheckoutPayment
import life.pilot.partner.sdk.model.CheckoutRequest
import life.pilot.partner.sdk.model.ClaimCreateRequest
import life.pilot.partner.sdk.model.ClaimItemRequest
import life.pilot.partner.sdk.webhooks.HmacVerifier
import life.pilot.partner.sdk.webhooks.WebhookParser
import life.pilot.partner.sdk.webhooks.WebhookPayload
import io.ktor.client.plugins.logging.LogLevel
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * End-to-end smoke test from the *consumer* classpath. Catches "compiles
 * but won't run" issues that the SDK's own unit tests can't see:
 *
 *   - api/implementation scope leaks (HttpLoggingInterceptor.Level, etc.)
 *   - Retrofit failing to find the kotlinx-serialization converter
 *   - typed PartnerException reaching consumer try/catch unwrapped
 *
 * Mirrors the steps a partner takes on day one.
 */
class SdkIntegrationSmokeTest {

    private lateinit var server: MockWebServer
    private lateinit var client: PilotPartnerClient

    @BeforeEach fun setUp() {
        server = MockWebServer().also { it.start() }
        client = PilotPartnerClient.builder()
            .apiKey("partner-key")
            .organizationUuid("org-uuid")
            .baseUrl(server.url("/").toString())
            .logging(LogLevel.NONE)
            .build()
    }

    @AfterEach fun tearDown() {
        server.shutdown()
    }

    @Test fun `consumer can list events end-to-end`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setResponseCode(200).setBody(
                """{"events":[{"eventUUID":"e1","name":"Show","startDate":"2026-06-01T20:00:00Z","endDate":"2026-06-02T00:00:00Z","venueName":"V"}],"nextCursor":null}""",
            ),
        )

        val page = client.events.list()
        assertThat(page.events).hasSize(1)
        assertThat(page.events[0].name).isEqualTo("Show")

        val req = server.takeRequest()
        assertThat(req.getHeader("X-API-Key")).isEqualTo("partner-key")
    }

    @Test fun `consumer catches PartnerException SoldOut by type`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setResponseCode(409).setBody(
                """{"code":"SOLD_OUT","message":"gone","ticketTypeUUID":"tt-1"}""",
            ),
        )

        val ex = assertThrows<PartnerException.SoldOut> {
            client.claims.create(
                eventUuid = "e",
                idempotencyKey = IdempotencyKey.generate(),
                body = ClaimCreateRequest(items = listOf(ClaimItemRequest("tt-1", 1))),
            )
        }
        assertThat(ex.ticketTypeUUID).isEqualTo("tt-1")
    }

    @Test fun `full claim-then-checkout flow returns an orderUUID`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setResponseCode(201).setBody(
                """{"claimId":"c1","claimIds":["c1"],"expiresAt":"2026-05-19T11:00:00Z","items":[]}""",
            ),
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setResponseCode(201).setBody(
                """{"orderUUID":"ord-1","orderStatus":"PAID","totalAmount":"65.00","patron":{"userUUID":"u1"}}""",
            ),
        )

        val claim = client.claims.create(
            eventUuid = "e",
            idempotencyKey = IdempotencyKey.generate(),
            body = ClaimCreateRequest(items = listOf(ClaimItemRequest("tt", 2))),
        )
        val order = client.claims.checkout(
            claimId = claim.claimId,
            idempotencyKey = IdempotencyKey.generate(),
            body = CheckoutRequest(
                patron = CheckoutPatron(email = "x@y.z"),
                payment = CheckoutPayment(paymentId = "p1", claimedAmount = "65.00"),
            ),
        )

        assertThat(order.orderUUID).isEqualTo("ord-1")
        assertThat(order.orderStatus).isEqualTo("PAID")
    }

    @Test fun `webhook parser is reachable from consumer code`() {
        val payload = WebhookParser().parse(
            """
            {
              "eventId":"e1","eventType":"order.created","createdAt":"t",
              "data":{"orderUUID":"o","eventUuid":"u","items":[{"ticketTypeUUID":"tt","quantity":1}],
                      "patron":{"userUuid":"u","emailHash":null,"phoneHash":null},"occurredAt":"t"}
            }
            """.trimIndent(),
        )
        assertThat(payload).isInstanceOf(WebhookPayload.OrderCreated::class)
    }

    @Test fun `HmacVerifier is reachable and rejects bad sig`() {
        val v = HmacVerifier(secret = "s", clock = { 0L })
        assertThat(v.verify("body", "t=0,v1=wrong")).isEqualTo(false)
    }
}
