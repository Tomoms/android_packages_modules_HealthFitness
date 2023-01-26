/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 */

/**
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.android.healthconnect.controller.tests.entrydetails

import androidx.lifecycle.MutableLiveData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.android.healthconnect.controller.R
import com.android.healthconnect.controller.dataentries.FormattedEntry.FormattedSessionEntry
import com.android.healthconnect.controller.entrydetails.DataEntryDetailsFragment
import com.android.healthconnect.controller.entrydetails.DataEntryDetailsViewModel
import com.android.healthconnect.controller.entrydetails.DataEntryDetailsViewModel.DateEntryFragmentState.Loading
import com.android.healthconnect.controller.entrydetails.DataEntryDetailsViewModel.DateEntryFragmentState.LoadingFailed
import com.android.healthconnect.controller.entrydetails.DataEntryDetailsViewModel.DateEntryFragmentState.WithData
import com.android.healthconnect.controller.permissions.data.HealthPermissionType.SLEEP
import com.android.healthconnect.controller.permissions.data.HealthPermissionType.STEPS
import com.android.healthconnect.controller.shared.DataType
import com.android.healthconnect.controller.tests.utils.NOW
import com.android.healthconnect.controller.tests.utils.launchFragment
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.hamcrest.Matchers.not
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever

@HiltAndroidTest
class DataEntryDetailsFragmentTest {
    @get:Rule val hiltRule = HiltAndroidRule(this)

    @BindValue
    val viewModel: DataEntryDetailsViewModel = mock(DataEntryDetailsViewModel::class.java)

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun dataEntriesDetailsInit_showsFragment() {
        whenever(viewModel.sessionData).thenReturn(MutableLiveData(WithData(emptyList())))

        launchFragment<DataEntryDetailsFragment>(
            DataEntryDetailsFragment.createBundle(permissionType = SLEEP, entryId = "1"))

        onView(withId(R.id.loading)).check(matches(not(isDisplayed())))
    }

    @Test
    fun dataEntriesDetailsInit_error_showsError() {
        whenever(viewModel.sessionData).thenReturn(MutableLiveData(LoadingFailed))

        launchFragment<DataEntryDetailsFragment>(
            DataEntryDetailsFragment.createBundle(permissionType = SLEEP, entryId = "1"))

        onView(withId(R.id.error_view)).check(matches(isDisplayed()))
    }

    @Test
    fun dataEntriesDetailsInit_loading_showsLoading() {
        whenever(viewModel.sessionData).thenReturn(MutableLiveData(Loading))

        launchFragment<DataEntryDetailsFragment>(
            DataEntryDetailsFragment.createBundle(permissionType = SLEEP, entryId = "1"))

        onView(withId(R.id.loading)).check(matches(isDisplayed()))
    }

    @Test
    fun dataEntriesDetailsInit_withData_showsItem() {
        whenever(viewModel.sessionData)
            .thenReturn(
                MutableLiveData(
                    WithData(
                        listOf(
                            FormattedSessionEntry(
                                uuid = "1",
                                header = "7:06 AM • TEST_APP_NAME",
                                headerA11y = "7:06 AM • TEST_APP_NAME",
                                title = "12 hour sleeping",
                                titleA11y = "12 hour sleeping",
                                dataType = DataType.SLEEP,
                                startTime = NOW,
                                notes = "notes")))))

        launchFragment<DataEntryDetailsFragment>(
            DataEntryDetailsFragment.createBundle(permissionType = STEPS, entryId = "1"))

        onView(withText("7:06 AM • TEST_APP_NAME")).check(matches(isDisplayed()))
        onView(withText("12 hour sleeping")).check(matches(isDisplayed()))
        onView(withText("notes")).check(matches(isDisplayed()))
    }
}