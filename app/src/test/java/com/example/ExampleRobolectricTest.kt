package com.example

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.test.core.app.ApplicationProvider
import com.example.ui.viewmodel.DocumentLockerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("FormReady AI", appName)
  }

  @Test
  fun `verify cache directory accessible for document preparation`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val cacheDir = context.cacheDir
    assertNotNull(cacheDir)
  }

  @Test
  fun `verify DocumentLockerViewModel instantiation via AndroidViewModelFactory`() {
    val application = ApplicationProvider.getApplicationContext<Application>()
    val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    val viewModel = factory.create(DocumentLockerViewModel::class.java)
    assertNotNull(viewModel)
    assertNotNull(viewModel.uiState.value)
  }

  @Test
  fun `verify DocumentLockerViewModel instantiation via DocumentLockerViewModel Factory`() {
    val application = ApplicationProvider.getApplicationContext<Application>()
    val extras = MutableCreationExtras().apply {
      set(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, application)
    }
    val viewModel = DocumentLockerViewModel.Factory.create(DocumentLockerViewModel::class.java, extras)
    assertNotNull(viewModel)
    assertNotNull(viewModel.uiState.value)
  }
}

