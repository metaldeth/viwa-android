package com.viwa.android.domain.usecase

import com.viwa.android.domain.model.SBPLink
import com.viwa.android.domain.repository.SBPRepository
import javax.inject.Inject

class GetSBPLinkUseCase
@Inject
constructor(
    private val repo: SBPRepository,
) {
    /** Drink purchase — legacy Paymaster QR. */
    suspend operator fun invoke(amountKopecks: Int): Result<SBPLink> = repo.getSBPLink(amountKopecks)
}
