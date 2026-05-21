package life.pilot.partner.testapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import life.pilot.partner.sdk.model.EventDetail
import life.pilot.partner.sdk.model.RegistrationCreateRequest
import life.pilot.partner.sdk.model.RtaCreateRequest
import life.pilot.partner.sdk.model.TicketTypeRow
import life.pilot.partner.ui.checkout.CheckoutSheet
import life.pilot.partner.ui.checkout.RegistrationFormSheet
import life.pilot.partner.ui.checkout.RtaFormSheet
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
    val ctx = LocalContext.current
    var screen: Screen by remember { mutableStateOf(Screen.List) }
    var pendingSelections: List<TicketSelection>? by remember { mutableStateOf(null) }
    var rtaForEvent: EventDetail? by remember { mutableStateOf(null) }
    var registrationForTicket: TicketTypeRow? by remember { mutableStateOf(null) }

    val onBack: () -> Unit = {
        when {
            rtaForEvent != null -> rtaForEvent = null
            registrationForTicket != null -> registrationForTicket = null
            pendingSelections != null -> pendingSelections = null
            screen is Screen.Detail -> screen = Screen.List
        }
    }
    // Android system back: same handler. Active whenever there's
    // somewhere to go back to so we don't intercept the activity-close
    // gesture on the events list.
    BackHandler(enabled = screen is Screen.Detail || pendingSelections != null || rtaForEvent != null || registrationForTicket != null) { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(when (val s = screen) {
                        is Screen.List -> "Events"
                        is Screen.Detail -> "Event details"
                    })
                },
                navigationIcon = {
                    if (screen is Screen.Detail) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    }
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
                onRequestToAttend = { event -> rtaForEvent = event },
                onRegister = { selections ->
                    selections.firstOrNull()?.let { registrationForTicket = it.ticketType }
                },
            )
        }

        rtaForEvent?.let { event ->
            RtaBottomSheet(
                client = client,
                event = event,
                onDismiss = { rtaForEvent = null },
                onSuccess = { rtaForEvent = null },
                showToast = { message -> android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_LONG).show() },
            )
        }

        registrationForTicket?.let { ticket ->
            (screen as? Screen.Detail)?.let { current ->
                RegistrationBottomSheet(
                    client = client,
                    eventUuid = current.eventUuid,
                    ticketType = ticket,
                    onDismiss = { registrationForTicket = null },
                    onSuccess = { registrationForTicket = null },
                    showToast = { message -> android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_LONG).show() },
                )
            }
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
    onRequestToAttend: (EventDetail) -> Unit,
    onRegister: (List<TicketSelection>) -> Unit,
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
        // isRequestToAttendEnabled defaults to event.rta?.enabled == true
        // registrationTicketTypesFor defaults to inventory.registrationTicketTypes
        onRequestToAttend = onRequestToAttend,
        onRegister = onRegister,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RtaBottomSheet(
    client: PilotPartnerClient,
    event: EventDetail,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
    showToast: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        RtaFormSheet(
            isSubmitting = submitting,
            error = error,
            onSubmit = { req ->
                submitting = true
                error = null
                scope.launch {
                    try {
                        val resp = client.events.requestToAttend(
                            eventUuid = event.eventUUID,
                            idempotencyKey = IdempotencyKey.generate(),
                            body = req,
                        )
                        showToast("RTA submitted: ${resp.message}")
                        onSuccess()
                    } catch (t: Throwable) {
                        error = t.message ?: "RTA submission failed"
                    } finally {
                        submitting = false
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegistrationBottomSheet(
    client: PilotPartnerClient,
    eventUuid: String,
    ticketType: TicketTypeRow,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
    showToast: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        RegistrationFormSheet(
            ticketType = ticketType,
            isSubmitting = submitting,
            error = error,
            onSubmit = { req ->
                submitting = true
                error = null
                scope.launch {
                    try {
                        val resp = client.events.createRegistration(
                            eventUuid = eventUuid,
                            idempotencyKey = IdempotencyKey.generate(),
                            body = req,
                        )
                        showToast("Registered (id ${resp.registrationId}, status ${resp.status})")
                        onSuccess()
                    } catch (t: Throwable) {
                        error = t.message ?: "Registration failed"
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
