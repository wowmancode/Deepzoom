// Compile-only stubs for the AndroidX / Material surface the app touches.
// Bodies are irrelevant; only signatures matter for type-checking.
package androidx.appcompat.app

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.View

open class AppCompatActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {}
}

open class AlertDialog : android.app.Dialog(null as Context) {
    open class Builder(context: Context) {
        fun setTitle(title: CharSequence?): Builder = this
        fun setMessage(message: CharSequence?): Builder = this
        fun setView(view: View?): Builder = this
        fun setCancelable(flag: Boolean): Builder = this
        fun setPositiveButton(text: CharSequence?, l: DialogInterface.OnClickListener?): Builder = this
        fun setNegativeButton(text: CharSequence?, l: DialogInterface.OnClickListener?): Builder = this
        fun setNeutralButton(text: CharSequence?, l: DialogInterface.OnClickListener?): Builder = this
        fun setOnDismissListener(l: DialogInterface.OnDismissListener?): Builder = this
        fun setItems(items: Array<CharSequence>?, l: DialogInterface.OnClickListener?): Builder = this
        fun create(): AlertDialog = AlertDialog()
        fun show(): AlertDialog = AlertDialog()
    }
}
