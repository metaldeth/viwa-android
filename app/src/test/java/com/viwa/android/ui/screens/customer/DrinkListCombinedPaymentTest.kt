package com.viwa.android.ui.screens.customer

import com.viwa.android.domain.model.CardPaymentResult
import com.viwa.android.domain.model.SBPLink
import com.viwa.android.domain.model.SBPStatus
import com.viwa.android.domain.model.customer.DrinkContainer
import com.viwa.android.domain.model.customer.DrinkDosage
import com.viwa.android.domain.model.customer.DrinkPrice
import com.viwa.android.domain.model.customer.DrinkProduct
import com.viwa.android.domain.model.customer.DrinkTaste
import com.viwa.android.domain.repository.SBPRepository
import com.viwa.android.services.payment.CardPaymentOrchestrator
import com.viwa.android.services.payment.ControllerSbpNotifyService
import com.viwa.android.services.payment.TerminalProductType
import com.viwa.android.services.preparing.PrepareDrinkResult
import com.viwa.android.services.preparing.PreparingManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DrinkListCombinedPaymentTest {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var mainDispatcher: CoroutineDispatcher

    @Before
    fun setup() {
        mainDispatcher = executor.asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        runBlocking {
            DrinkListViewModelTestSupport.clearTrackedViewModels(mainDispatcher)
        }
        Dispatchers.resetMain()
        executor.shutdown()
        if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
            executor.shutdownNow()
        }
    }

    private suspend fun awaitCondition(timeoutMs: Long = 5000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            withContext(mainDispatcher) {}
            yield()
        }
        return false
    }

    private suspend fun flushMain(times: Int = 12) {
        repeat(times) {
            withContext(mainDispatcher) {}
            yield()
        }
    }

    private fun sampleContainer(containerNumber: Int = 2): DrinkContainer {
        val taste = DrinkTaste(1, "Cola", null, null)
        val product =
            DrinkProduct(
                id = 1,
                name = "Coke",
                taste = taste,
                dosage = DrinkDosage(1.0, 300, 1.0, 1.0),
                dPrices = listOf(DrinkPrice(300, 100)),
            )
        return DrinkContainer(
            containerNumber = containerNumber,
            sodaStatus = null,
            product = product,
            productUuid = "test-product-uuid",
            volumeMl = 1000,
            minVolumeMl = 0,
            isActive = true,
        )
    }

    private class CombinedPaymentMocks {
        val orch = mockk<CardPaymentOrchestrator>(relaxUnitFun = true)
        val getSbp = mockk<com.viwa.android.domain.usecase.GetSBPLinkUseCase>(relaxUnitFun = true)
        val checkSbp = mockk<com.viwa.android.domain.usecase.CheckSBPStatusUseCase>(relaxUnitFun = true)
        val preparing = mockk<PreparingManager>(relaxUnitFun = true)
        val sbpRepository = mockk<SBPRepository>(relaxUnitFun = true)

        fun applyDefaults() {
            coEvery { getSbp(any()) } returns
                Result.success(SBPLink(orderId = "order-1", url = "https://pay.test", qrData = "qr-data"))
            coEvery { checkSbp(any()) } returns Result.success(SBPStatus.Pending)
            coEvery { sbpRepository.cancelSBPLink(any()) } returns Result.success(Unit)
            coEvery { orch.pay(any(), any(), any(), any()) } returns CardPaymentResult.Success
            coEvery {
                preparing.prepareDrink(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns PrepareDrinkResult.Ok(estSeconds = 20)
            coEvery { preparing.validateDrinkPreparation(any()) } returns null
        }
    }

    private fun combinedVm(configure: CombinedPaymentMocks.() -> Unit = {}): Pair<DrinkListViewModel, CombinedPaymentMocks> {
        val mocks = CombinedPaymentMocks().apply {
            applyDefaults()
            configure()
        }
        val vm =
            DrinkListViewModelTestSupport.createViewModel(
                getSBPLinkUseCase = mocks.getSbp,
                checkSBPStatusUseCase = mocks.checkSbp,
                cardPaymentOrchestrator = mocks.orch,
                preparingManager = mocks.preparing,
                sbpRepository = mocks.sbpRepository,
            )
        return vm to mocks
    }

    @Test
    fun combinedStart_initiatesBothCardAndSbpChannels() =
        runBlocking {
            val (vm, mocks) =
                combinedVm {
                    coEvery { orch.pay(any(), any(), any(), any()) } coAnswers {
                        kotlinx.coroutines.awaitCancellation()
                    }
                }
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    activeContainer = sampleContainer(3),
                    selectedVolumeMl = 300,
                    paymentSheetVisible = true,
                    paymentSheetStep = PaymentSheetStep.Combined,
                ),
            )
            vm.startCombinedDrinkPayment { _, _, _, _, _, _ -> }
            flushMain(24)
            assertTrue(awaitCondition(timeoutMs = 15_000) { vm.state.value.sbpLink != null })
            flushMain()
            coVerify(exactly = 1) { mocks.orch.pay(TerminalProductType.Drink, 100, 3, sbp = false) }
            coVerify(exactly = 1) { mocks.getSbp(100 * 100) }
            vm.dismissPaymentSheet()
            flushMain()
        }

    @Test
    fun cardSuccess_completesPourOnceWithoutSecondCardPay() =
        runBlocking {
            val (vm, mocks) = combinedVm()
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    activeContainer = sampleContainer(),
                    selectedVolumeMl = 300,
                    paymentSheetVisible = true,
                    paymentSheetStep = PaymentSheetStep.Combined,
                ),
            )
            val navigated = AtomicBoolean(false)
            vm.startCombinedDrinkPayment { _, _, _, _, _, _ -> navigated.set(true) }
            assertTrue(awaitCondition(timeoutMs = 10_000) { navigated.get() })
            flushMain()
            assertFalse(vm.state.value.isProcessingPay)
            coVerify(exactly = 1) { mocks.orch.pay(any(), any(), any(), any()) }
            coVerify(exactly = 1) {
                mocks.preparing.prepareDrink(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun prepareDrinkErrorAfterClaim_keepsSettledAndBlocksRetry() =
        runBlocking {
            val (vm, mocks) =
                combinedVm {
                    coEvery {
                        preparing.prepareDrink(
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                        )
                    } returns PrepareDrinkResult.Error(errorCode = "TEST", message = "no water")
                }
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    activeContainer = sampleContainer(),
                    selectedVolumeMl = 300,
                    paymentSheetVisible = true,
                    paymentSheetStep = PaymentSheetStep.Combined,
                    freeMode = false,
                ),
            )
            vm.startCombinedDrinkPayment { _, _, _, _, _, _ -> }
            assertTrue(awaitCondition(timeoutMs = 10_000) { vm.isCombinedPaymentSettledForUnitTests() })
            flushMain()
            assertTrue(vm.state.value.combinedPaymentConfirmed)
            assertEquals(
                DrinkListViewModel.COMBINED_PAID_PREPARE_FAILED_MESSAGE,
                vm.state.value.paymentError,
            )
            assertFalse(vm.state.value.isProcessingPay)
            vm.startCombinedDrinkPayment { _, _, _, _, _, _ -> }
            flushMain(24)
            coVerify(exactly = 1) { mocks.orch.pay(any(), any(), any(), any()) }
        }

    @Test
    fun dismissAfterClaim_isIgnoredAndDoesNotAllowRetry() =
        runBlocking {
            val (vm, mocks) =
                combinedVm {
                    coEvery {
                        preparing.prepareDrink(
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                        )
                    } returns PrepareDrinkResult.Error(errorCode = "TEST", message = "no water")
                }
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    activeContainer = sampleContainer(),
                    selectedVolumeMl = 300,
                    paymentSheetVisible = true,
                    paymentSheetStep = PaymentSheetStep.Combined,
                ),
            )
            vm.startCombinedDrinkPayment { _, _, _, _, _, _ -> }
            assertTrue(awaitCondition(timeoutMs = 10_000) { vm.state.value.combinedPaymentConfirmed })
            flushMain()
            vm.dismissPaymentSheet()
            flushMain()
            assertTrue(vm.state.value.paymentSheetVisible)
            assertTrue(vm.isCombinedPaymentSettledForUnitTests())
            vm.startCombinedDrinkPayment { _, _, _, _, _, _ -> }
            flushMain(24)
            coVerify(exactly = 1) { mocks.orch.pay(any(), any(), any(), any()) }
        }

    @Test
    fun clearSelectionAfterPrepareError_isIgnored() =
        runBlocking {
            val (vm, mocks) =
                combinedVm {
                    coEvery {
                        preparing.prepareDrink(
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                        )
                    } returns PrepareDrinkResult.Error(errorCode = "TEST", message = "no water")
                }
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    activeContainer = sampleContainer(),
                    selectedVolumeMl = 300,
                    paymentSheetVisible = true,
                    paymentSheetStep = PaymentSheetStep.Combined,
                ),
            )
            vm.startCombinedDrinkPayment { _, _, _, _, _, _ -> }
            assertTrue(awaitCondition(timeoutMs = 10_000) { vm.state.value.combinedPaymentConfirmed })
            flushMain()
            vm.clearSelection()
            flushMain(24)
            assertTrue(vm.state.value.paymentSheetVisible)
            assertTrue(vm.isCombinedPaymentSettledForUnitTests())
            coVerify(exactly = 1) { mocks.orch.pay(any(), any(), any(), any()) }
        }

    @Test
    fun explicitRecoveryAfterPrepareError_closesOverlayAndReleasesSession() =
        runBlocking {
            val (vm, mocks) =
                combinedVm {
                    coEvery {
                        preparing.prepareDrink(
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                        )
                    } returns PrepareDrinkResult.Error(errorCode = "TEST", message = "no water")
                }
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    activeContainer = sampleContainer(),
                    selectedVolumeMl = 300,
                    paymentSheetVisible = true,
                    paymentSheetStep = PaymentSheetStep.Combined,
                ),
            )
            vm.startCombinedDrinkPayment { _, _, _, _, _, _ -> }
            assertTrue(awaitCondition(timeoutMs = 10_000) { vm.state.value.combinedPaymentConfirmed })
            flushMain()
            vm.exitCombinedPaymentRecoveryToMenu()
            assertTrue(awaitCondition(timeoutMs = 10_000) { !vm.state.value.paymentSheetVisible })
            flushMain()
            assertFalse(vm.isCombinedPaymentSettledForUnitTests())
            assertFalse(vm.state.value.combinedPaymentConfirmed)
            assertNull(vm.state.value.activeContainer)
            vm.setUiStateForUnitTests(
                vm.state.value.copy(
                    activeContainer = sampleContainer(),
                    selectedVolumeMl = 300,
                    paymentSheetVisible = true,
                    paymentSheetStep = PaymentSheetStep.Combined,
                ),
            )
            vm.startCombinedDrinkPayment { _, _, _, _, _, _ -> }
            flushMain(24)
            coVerify(exactly = 2) { mocks.orch.pay(any(), any(), any(), any()) }
            coVerify(exactly = 2) {
                mocks.preparing.prepareDrink(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun devPourAfterPrepareError_isIgnored() =
        runBlocking {
            val (vm, mocks) =
                combinedVm {
                    coEvery {
                        preparing.prepareDrink(
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                        )
                    } returns PrepareDrinkResult.Error(errorCode = "TEST", message = "no water")
                }
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    activeContainer = sampleContainer(),
                    selectedVolumeMl = 300,
                    paymentSheetVisible = true,
                    paymentSheetStep = PaymentSheetStep.Combined,
                    freeMode = true,
                ),
            )
            vm.startCombinedDrinkPayment { _, _, _, _, _, _ -> }
            assertTrue(awaitCondition(timeoutMs = 10_000) { vm.state.value.combinedPaymentConfirmed })
            flushMain()
            vm.devPourWithoutPayment { _, _, _, _, _, _ -> }
            flushMain(24)
            assertTrue(vm.state.value.paymentSheetVisible)
            coVerify(exactly = 1) {
                mocks.preparing.prepareDrink(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun dismissCombined_cancelsCardPayment() =
        runBlocking {
            val (vm, mocks) =
                combinedVm {
                    coEvery { orch.pay(any(), any(), any(), any()) } coAnswers {
                        kotlinx.coroutines.awaitCancellation()
                    }
                }
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    activeContainer = sampleContainer(),
                    selectedVolumeMl = 300,
                    paymentSheetVisible = true,
                    paymentSheetStep = PaymentSheetStep.Combined,
                    sbpLink = SBPLink("order-1", "https://pay.test", "qr"),
                ),
            )
            vm.startCombinedDrinkPayment { _, _, _, _, _, _ -> }
            flushMain()
            vm.dismissPaymentSheet()
            flushMain(24)
            coVerify(atLeast = 1) { mocks.orch.cancelActivePayment() }
            coVerify(atLeast = 1) { mocks.sbpRepository.cancelSBPLink("order-1") }

            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    activeContainer = sampleContainer(),
                    selectedVolumeMl = 300,
                    paymentSheetVisible = true,
                    paymentSheetStep = PaymentSheetStep.Combined,
                ),
            )
            vm.startCombinedDrinkPayment { _, _, _, _, _, _ -> }
            flushMain(24)
            coVerify(exactly = 2) { mocks.orch.pay(any(), any(), any(), any()) }
            vm.dismissPaymentSheet()
            flushMain()
        }

    @Test
    fun devPourWithoutPayment_stopsCombinedChannelsBeforeFreePour() =
        runBlocking {
            val (vm, mocks) =
                combinedVm {
                    coEvery { orch.pay(any(), any(), any(), any()) } coAnswers {
                        try {
                            kotlinx.coroutines.awaitCancellation()
                        } catch (_: kotlinx.coroutines.CancellationException) {
                            CardPaymentResult.Success
                        }
                    }
                }
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    activeContainer = sampleContainer(),
                    selectedVolumeMl = 300,
                    paymentSheetVisible = true,
                    paymentSheetStep = PaymentSheetStep.Combined,
                    freeMode = true,
                ),
            )
            vm.startCombinedDrinkPayment { _, _, _, _, _, _ -> }
            assertTrue(awaitCondition(timeoutMs = 10_000) { vm.state.value.sbpLink != null })
            flushMain()
            vm.devPourWithoutPayment { _, _, _, _, _, _ -> }
            assertTrue(awaitCondition(timeoutMs = 10_000) { !vm.state.value.paymentSheetVisible })
            flushMain(24)
            coVerify(atLeast = 1) { mocks.orch.cancelActivePayment() }
            coVerify(atLeast = 1) { mocks.sbpRepository.cancelSBPLink("order-1") }
            coVerify(exactly = 1) {
                mocks.preparing.prepareDrink(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    saleTotalPriceRub = 0.0,
                    salePayMethod = "FREE",
                    any(),
                )
            }
            coVerify(exactly = 1) {
                mocks.preparing.prepareDrink(any(), any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun cardFailure_leavesCombinedStepWhileSbpActive() =
        runBlocking {
            val (vm, mocks) =
                combinedVm {
                    coEvery { orch.pay(any(), any(), any(), any()) } returns CardPaymentResult.Failed("declined")
                }
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    activeContainer = sampleContainer(),
                    selectedVolumeMl = 300,
                    paymentSheetVisible = true,
                    paymentSheetStep = PaymentSheetStep.Combined,
                ),
            )
            vm.startCombinedDrinkPayment { _, _, _, _, _, _ -> }
            flushMain(48)
            assertEquals(PaymentSheetStep.Combined, vm.state.value.paymentSheetStep)
            assertFalse(vm.isCombinedPaymentSettledForUnitTests())
            assertTrue(vm.state.value.paymentError.isNullOrBlank())
            vm.dismissPaymentSheet()
            flushMain()
        }

    @Test
    fun sbpSuccess_completesSinglePrepareDrink() =
        runBlocking {
            val (vm, mocks) =
                combinedVm {
                    coEvery { orch.pay(any(), any(), any(), any()) } coAnswers {
                        kotlinx.coroutines.awaitCancellation()
                    }
                    coEvery { checkSbp(any()) } returns Result.success(SBPStatus.Success)
                }
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    activeContainer = sampleContainer(),
                    selectedVolumeMl = 300,
                    paymentSheetVisible = true,
                    paymentSheetStep = PaymentSheetStep.Combined,
                ),
            )
            val navigated = AtomicBoolean(false)
            vm.startCombinedDrinkPayment { _, _, _, _, _, _ -> navigated.set(true) }
            assertTrue(awaitCondition(timeoutMs = 10_000) { vm.isCombinedPaymentSettledForUnitTests() })
            assertTrue(awaitCondition(timeoutMs = 5_000) { navigated.get() })
            flushMain()
            coVerify(exactly = 1) { mocks.orch.pay(any(), any(), any(), sbp = false) }
            coVerify(exactly = 1) {
                mocks.preparing.prepareDrink(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun terminalStatus_doesNotDowngradeSuccessAfterClaim() =
        runBlocking {
            val (vm, mocks) = combinedVm()
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    paymentSheetStep = PaymentSheetStep.Combined,
                    cardPaymentUiStatus = CardPaymentUiStatus.Success,
                    combinedPaymentConfirmed = true,
                ),
            )
            vm.applyTerminalBannerForCombinedStatusTest("Приложите карту к терминалу")
            assertEquals(CardPaymentUiStatus.Success, vm.state.value.cardPaymentUiStatus)
        }

    @Test
    fun combinedStart_sets60SecondTimer() =
        runBlocking {
            val (vm, mocks) =
                combinedVm {
                    coEvery { orch.pay(any(), any(), any(), any()) } coAnswers {
                        kotlinx.coroutines.awaitCancellation()
                    }
                }
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    activeContainer = sampleContainer(),
                    selectedVolumeMl = 300,
                    paymentSheetVisible = true,
                    paymentSheetStep = PaymentSheetStep.Combined,
                ),
            )
            vm.startCombinedDrinkPayment { _, _, _, _, _, _ -> }
            assertTrue(
                awaitCondition {
                    vm.state.value.sbpRemainingSeconds == DrinkListViewModel.COMBINED_PAYMENT_TIMEOUT_SECONDS
                },
            )
            flushMain()
            vm.dismissPaymentSheet()
            flushMain()
        }

    @Test
    fun combinedTimeout_returnsToMenuAndResetsPaymentState() =
        runBlocking {
            val (vm, mocks) = combinedVm()
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    activeContainer = sampleContainer(),
                    selectedVolumeMl = 300,
                    paymentSheetVisible = true,
                    paymentSheetStep = PaymentSheetStep.Combined,
                    isProcessingPay = true,
                    sbpLink = SBPLink(orderId = "order-1", url = "https://pay.test", qrData = "qr-data"),
                    sbpRemainingSeconds = 0,
                ),
            )

            vm.expireCombinedPaymentForUnitTests()

            assertFalse(vm.state.value.paymentSheetVisible)
            assertNull(vm.state.value.activeContainer)
            assertNull(vm.state.value.selectedVolumeMl)
            assertEquals(PaymentSheetStep.MethodChoice, vm.state.value.paymentSheetStep)
            assertEquals(0, vm.state.value.sbpRemainingSeconds)
            assertFalse(vm.state.value.isProcessingPay)
            assertFalse(vm.isCombinedPaymentSettledForUnitTests())
            coVerify(atLeast = 1) { mocks.orch.cancelActivePayment() }
            coVerify(exactly = 1) { mocks.sbpRepository.cancelSBPLink("order-1") }
        }

    @Test
    fun combinedTimeout_rejectsLateCardSuccessDuringCancellation() =
        runBlocking {
            val (vm, mocks) =
                combinedVm {
                    coEvery { orch.pay(any(), any(), any(), any()) } coAnswers {
                        try {
                            kotlinx.coroutines.awaitCancellation()
                        } catch (_: kotlinx.coroutines.CancellationException) {
                            CardPaymentResult.Success
                        }
                    }
                }
            vm.setUiStateForUnitTests(
                DrinkListUiState(
                    activeContainer = sampleContainer(),
                    selectedVolumeMl = 300,
                    paymentSheetVisible = true,
                    paymentSheetStep = PaymentSheetStep.Combined,
                ),
            )
            vm.startCombinedDrinkPayment { _, _, _, _, _, _ -> }
            assertTrue(awaitCondition(timeoutMs = 10_000) { vm.state.value.sbpLink != null })

            vm.expireCombinedPaymentForUnitTests()
            flushMain(24)

            assertFalse(vm.state.value.paymentSheetVisible)
            assertNull(vm.state.value.activeContainer)
            assertFalse(vm.state.value.combinedPaymentConfirmed)
            assertFalse(vm.isCombinedPaymentSettledForUnitTests())
            coVerify(exactly = 0) {
                mocks.preparing.prepareDrink(any(), any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun claimCombinedPaymentWinner_isIdempotent() {
        val settled = AtomicBoolean(false)
        assertTrue(
            DrinkListCardPaymentFlow.claimCombinedPaymentWinner(
                settled,
                CombinedPaymentWinner.Card,
            ),
        )
        assertFalse(
            DrinkListCardPaymentFlow.claimCombinedPaymentWinner(
                settled,
                CombinedPaymentWinner.Sbp,
            ),
        )
    }

    @Test
    fun runDrinkPaymentBeforePour_skipsSecondCardWhenAlreadyPaid() =
        runBlocking {
            val orch = mockk<CardPaymentOrchestrator>(relaxUnitFun = true)
            val sbp = mockk<ControllerSbpNotifyService>(relaxUnitFun = true)
            DrinkListCardPaymentFlow.runDrinkPaymentBeforePour(
                container = sampleContainer(),
                volume = 300,
                sbp = false,
                cardPaymentOrchestrator = orch,
                controllerSbpNotifyService = sbp,
                cardAlreadyPaid = true,
            )
            coVerify(exactly = 0) { orch.pay(any(), any(), any(), any()) }
        }
}
