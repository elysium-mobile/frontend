package com.elysium.softwork.payment.membership.presentation.views.company

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elysium.softwork.R
import com.elysium.softwork.iam.domain.model.Company
import com.elysium.softwork.payment.membership.presentation.viewmodel.CompanySelectionViewModel
import com.elysium.softwork.shared.presentation.components.SoftWorkCard
import com.elysium.softwork.shared.presentation.theme.AccentDark
import com.elysium.softwork.shared.presentation.theme.AccentWhite
import com.elysium.softwork.shared.presentation.theme.Danger
import com.elysium.softwork.shared.presentation.theme.PrimaryNavy

/**
 * Onboarding **company-selection** step, reached after the demo membership bypass creates a
 * membership. Lists the corporate directory (`GET /api/v1/companies`); tapping a company associates
 * it (plus the fresh [membershipId]) onto the worker's account (`PUT /api/v1/user_accounts/{id}`).
 *
 * On a successful association the ViewModel opens the persisted membership gate, which the
 * `MainActivity` root observes — hot-swapping the worker straight into the authenticated main shell
 * (Home dashboard + company-scoped forum) and discarding this onboarding back stack. There is
 * therefore no explicit forward navigation here.
 *
 * @param membershipId the membership created by the bypass, threaded in as a nav argument.
 * @param viewModel injected UI state holder (default resolves from the service-locator factory).
 */
@Composable
fun CompanySelectionScreen(
    membershipId: Long,
    viewModel: CompanySelectionViewModel = viewModel(factory = CompanySelectionViewModel.Factory),
) {
    val companies: List<Company> by viewModel.companies.collectAsStateWithLifecycle()
    val isLoading: Boolean by viewModel.isLoading.collectAsStateWithLifecycle()
    val isAssociating: Boolean by viewModel.isAssociating.collectAsStateWithLifecycle()
    val errorMessage: String? by viewModel.errorMessage.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AccentWhite),
    ) {
        CompanyHeader()

        errorMessage?.let { message ->
            Text(
                text = message,
                color = Danger,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        if (isLoading && companies.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PrimaryNavy)
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = WindowInsets.navigationBars
                .add(WindowInsets(left = 20.dp, top = 8.dp, right = 20.dp, bottom = 16.dp))
                .asPaddingValues(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = companies, key = { it.company_id ?: 0L }) { company ->
                CompanyCard(
                    company = company,
                    enabled = !isAssociating,
                    onClick = {
                        company.company_id?.let { viewModel.selectCompany(membershipId, it) }
                    },
                )
            }
        }
    }
}

@Composable
private fun CompanyHeader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = stringResource(R.string.payment_company_selection_title),
            color = PrimaryNavy,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun CompanyCard(company: Company, enabled: Boolean, onClick: () -> Unit) {
    SoftWorkCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = company.name.orEmpty(),
                color = PrimaryNavy,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            company.ruc?.takeIf { it.isNotBlank() }?.let { ruc ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = ruc,
                    color = AccentDark,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                )
            }
        }
    }
}
