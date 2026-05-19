package life.pilot.partner.testapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import life.pilot.partner.sdk.PilotPartnerClient
import life.pilot.partner.sdk.auth.IdempotencyKey
import life.pilot.partner.sdk.model.ClaimCreateRequest
import life.pilot.partner.sdk.model.ClaimItemRequest
import life.pilot.partner.sdk.model.CheckoutPatron
import life.pilot.partner.sdk.model.CheckoutPayment
import life.pilot.partner.sdk.model.CheckoutRequest
import life.pilot.partner.ui.checkout.CheckoutSheet
import life.pilot.partner.ui.event.EventDetailScreen
import life.pilot.partner.ui.event.EventListWithFilters
import life.pilot.partner.ui.event.TicketSelection
import life.pilot.partner.ui.theme.PilotPartnerTheme
import life.pilot.partner.ui.viewmodel.EventsViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PilotPartnerTheme {
                AppRoot(client = PartnerClientHolder.client)
            }
        }
    }
}

private sealed interface Screen {
    data object List : Screen
    data class Detail(val eventUuid: String) : Screen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(client: PilotPartnerClient) {
    val vm: EventsViewModel = viewModel(factory = EventsViewModelFactory(client))
    var screen: Screen by remember { mutableStateOf(Screen.List) }
    var pendingSelections: List<TicketSelection>? by remember { mutableStateOf(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(when (val s = screen) {
                        is Screen.List -> "Events"
                        is Screen.Detail -> "Event details"
                    })
                },
            )
        },
    ) { padding ->
        when (val s = screen) {
            is Screen.List -> EventListPane(
                vm = vm,
                contentPadding = padding,
                onEventClick = { evt ->
                    vm.loadEvent(evt.eventUUID)
                    screen = Screen.Detail(evt.eventUUID)
                },
            )

            is Screen.Detail -> EventDetailPane(
                vm = vm,
                contentPadding = padding,
                eventUuid = s.eventUuid,
                onContinue = { selections -> pendingSelections = selections },
            )
        }

        if (pendingSelections != null && screen is Screen.Detail) {
            CheckoutBottomSheet(
                client = client,
                eventUuid = (screen as Screen.Detail).eventUuid,
                selections = pendingSelections!!,
                onDismiss = { pendingSelections = null },
                onCompleted = {
                    pendingSelections = null
                    screen = Screen.List
                },
            )
        }
    }
}

@Composable
private fun EventListPane(
    vm: EventsViewModel,
    contentPadding: PaddingValues,
    onEventClick: (life.pilot.partner.sdk.model.EventListItem) -> Unit,
) {
    val state by vm.events.collectAsState()
    val filters by vm.filters.collectAsState()
    // Scaffold padding pushes the WHOLE component (filter bar + list)
    // below the TopAppBar via the modifier. The inner LazyColumn's own
    // contentPadding stays at its default (16dp around items).
    // imageUrlFor defaults to { it.imageUrl } — partner API returns it.
    EventListWithFilters(
        state = state,
        filters = filters,
        onFiltersChange = vm::updateFilters,
        onLoadMore = { vm.loadMoreEvents() },
        onRefresh = { vm.refreshEvents() },
        onEventClick = onEventClick,
        modifier = Modifier
            .padding(contentPadding)
            .fillMaxSize(),
    )
}

@Composable
private fun EventDetailPane(
    vm: EventsViewModel,
    contentPadding: PaddingValues,
    eventUuid: String,
    onContinue: (List<TicketSelection>) -> Unit,
) {
    val detail by vm.detail.collectAsState()
    val inventory by vm.inventory.collectAsState()
    val error by vm.detailError.collectAsState()
    val loading by vm.detailLoading.collectAsState()
    val d = detail ?: return
    EventDetailScreen(
        event = d,
        inventory = inventory,
        isLoading = loading,
        error = error,
        modifier = Modifier.padding(contentPadding),
        // Demo wiring for RTA + Registration. The partner API doesn't yet
        // expose this state; real partners would source it from their own
        // backend keyed by eventUUID. Toggle the predicate to true to see
        // the buttons render.
        isRequestToAttendEnabled = { false },
        registrationTicketTypesFor = { emptyList() },
        onRequestToAttend = { /* TODO: partner-side RTA flow */ },
        onRegister = { /* TODO: partner-side free/RSVP checkout */ },
        onContinue = onContinue,
        onRetry = { vm.loadEvent(eventUuid) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CheckoutBottomSheet(
    client: PilotPartnerClient,
    eventUuid: String,
    selections: List<TicketSelection>,
    onDismiss: () -> Unit,
    onCompleted: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        CheckoutSheet(
            isSubmitting = submitting,
            error = error,
            onSubmit = { patron ->
                submitting = true
                error = null
                scope.launch {
                    try {
                        val claim = client.claims.create(
                            eventUuid = eventUuid,
                            idempotencyKey = IdempotencyKey.generate(),
                            body = ClaimCreateRequest(
                                items = selections.map {
                                    ClaimItemRequest(it.ticketType.ticketTypeUUID, it.quantity)
                                },
                            ),
                        )
                        val total = selections.sumOf {
                            it.ticketType.price.toBigDecimal() * it.quantity.toBigDecimal()
                        }
                        client.claims.checkout(
                            claimId = claim.claimId,
                            idempotencyKey = IdempotencyKey.generate(),
                            body = CheckoutRequest(
                                patron = patron,
                                payment = CheckoutPayment(
                                    paymentId = "test-${System.currentTimeMillis()}",
                                    claimedAmount = total.toPlainString(),
                                ),
                            ),
                        )
                        onCompleted()
                    } catch (t: Throwable) {
                        error = t.message ?: "Checkout failed"
                    } finally {
                        submitting = false
                    }
                }
            },
        )
    }
}

private class EventsViewModelFactory(private val client: PilotPartnerClient) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        EventsViewModel(client) as T
}
