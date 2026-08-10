package com.viwa.android.domain.usecase

import com.viwa.android.domain.model.SBPStatus
import com.viwa.android.domain.repository.SBPRepository
import javax.inject.Inject

class CheckSBPStatusUseCase
@Inject
constructor(
    private val repo: SBPRepository,
) {
    /** Drink purchase — Paymaster polling. */
    suspend operator fun invoke(orderId: String): Result<SBPStatus> = repo.getSBPLinkStatus(orderId)
}
