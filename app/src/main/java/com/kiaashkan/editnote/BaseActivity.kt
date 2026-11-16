package com.kiaashkan.editnote

import android.app.Activity
import android.content.Context
import android.os.Bundle

/**
 * Item 99: shared base for the three activities — applies the
 * in-app language override (see [LocaleHelper]) before creation,
 * and rebuilds the activity if the language was changed (in
 * Settings) while it sat in the back stack.
 */
abstract class BaseActivity : Activity() {

    private var createdLanguage: String = ""

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdLanguage = LocaleHelper.selectedTag(this)
    }

    override fun onResume() {
        super.onResume()
        if (createdLanguage != LocaleHelper.selectedTag(this)) recreate()
    }
}
