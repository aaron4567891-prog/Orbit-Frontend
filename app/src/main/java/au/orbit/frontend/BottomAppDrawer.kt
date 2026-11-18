package au.orbit.frontend

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import java.util.concurrent.Executors

/** A separate drawer window on the companion display; never navigates the main screen. */
object BottomAppDrawer {
    private val worker=Executors.newSingleThreadExecutor()
    private var showing=java.lang.ref.WeakReference<AlertDialog>(null)
    fun close() { showing.get()?.dismiss() }

    fun show(
        activity:Activity,
        pickerTitle:String="Choose dock app",
        canRemove:Boolean=true,
        onSelected:((ComponentName?)->Unit)?=null
    ) {
        if(showing.get()?.isShowing==true)return
        fun dp(n:Int)=(n*activity.resources.displayMetrics.density).toInt()
        val panel=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(8))
        }
        val search=EditText(activity).apply {
            hint="Search apps";setSingleLine();textSize=16f;showSoftInputOnFocus=false
            isHapticFeedbackEnabled=false
            inputType=android.text.InputType.TYPE_CLASS_TEXT
            imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
        }
        val status=TextView(activity).apply { text="Loading apps…";textSize=13f;setPadding(0,dp(8),0,dp(8)) }
        val grid=GridView(activity).apply {
            numColumns=5;stretchMode=GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing=dp(8);horizontalSpacing=dp(6);isFocusableInTouchMode=true
            clipToPadding=false;setPadding(0,dp(8),0,dp(8))
        }
        val close=Button(activity).apply { SelectionStyle.apply(this); text="Close";isAllCaps=false;textSize=14f }
        panel.addView(LinearLayout(activity).apply {
            gravity=Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text=if(onSelected==null)"Android apps" else pickerTitle;textSize=20f;setTextColor(Color.WHITE)
            },LinearLayout.LayoutParams(0,-2,1f))
            if(onSelected!=null && canRemove)addView(Button(activity).apply {
                text="Remove app";isAllCaps=false;SelectionStyle.apply(this)
                setOnClickListener { runCatching { onSelected(null) }.onSuccess { close.performClick() }.onFailure { Toast.makeText(activity,it.message,Toast.LENGTH_LONG).show() } }
            },LinearLayout.LayoutParams(-2,dp(42)))
            addView(close,LinearLayout.LayoutParams(-2,dp(42)))
        },LinearLayout.LayoutParams(-1,dp(48)))
        panel.addView(search,LinearLayout.LayoutParams(-1,dp(48)))
        panel.addView(status)
        panel.addView(grid,LinearLayout.LayoutParams(-1,0,1f))
        val keyboard=CompactAppKeyboard(activity,search) { grid.requestFocus() }
        panel.addView(keyboard)
        search.setOnClickListener { keyboard.show() }
        search.setOnTouchListener { view,event ->
            if(event.action==android.view.MotionEvent.ACTION_UP) { view.requestFocus();view.performClick() }
            true
        }
        val dialog=AlertDialog.Builder(activity).setView(panel).create()
        close.setOnClickListener { dialog.dismiss() }
        showing=java.lang.ref.WeakReference(dialog)
        var restoreFocus=true
        var apps=emptyList<InstalledApps.Entry>()
        var filtered=apps
        fun open(position:Int) {
            val info=filtered.getOrNull(position)?.info?.activityInfo ?: return
            if(onSelected!=null) {
                runCatching { onSelected(ComponentName(info.packageName,info.name)) }.onSuccess { dialog.dismiss() }
                    .onFailure { Toast.makeText(activity,it.message,Toast.LENGTH_LONG).show() }
                return
            }
            restoreFocus=false
            if(AppDock.launchApp(activity,ComponentName(info.packageName,info.name)))dialog.dismiss() else restoreFocus=true
        }
        val adapter=object:BaseAdapter() {
            override fun getCount()=filtered.size
            override fun getItem(position:Int)=filtered[position]
            override fun getItemId(position:Int)=position.toLong()
            override fun getView(position:Int,convertView:View?,parent:ViewGroup):View {
                val app=filtered[position]
                val cell=(convertView as? LinearLayout) ?: LinearLayout(activity).apply {
                    orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
                    setPadding(dp(3),dp(6),dp(3),dp(6))
                    addView(ImageView(activity).apply { scaleType=ImageView.ScaleType.FIT_CENTER },LinearLayout.LayoutParams(dp(46),dp(46)))
                    addView(TextView(activity).apply {
                        textSize=11f;setTextColor(Color.WHITE);gravity=Gravity.CENTER;maxLines=2
                        ellipsize=android.text.TextUtils.TruncateAt.END
                    },LinearLayout.LayoutParams(-1,dp(34)))
                }
                (cell.getChildAt(0) as ImageView).setImageDrawable(app.icon.constantState?.newDrawable(activity.resources) ?: app.icon)
                (cell.getChildAt(1) as TextView).text=app.label
                cell.contentDescription=app.label
                return cell
            }
        }
        grid.adapter=adapter
        fun filter() {
            val query=search.text.toString().trim()
            // Narrow by the beginning of the displayed app name on every keystroke.
            filtered=apps.filter { it.label.startsWith(query,true) }
            adapter.notifyDataSetChanged()
            status.text=if(filtered.isEmpty())"No apps found" else "Hold an app or press Y for options"
            status.visibility=View.VISIBLE
            grid.setSelection(0)
        }
        search.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int)=filter()
            override fun afterTextChanged(s:Editable?)=Unit
        })
        search.setOnEditorActionListener { _,_,_ ->
            keyboard.hide();true
        }
        grid.setOnItemClickListener { _,_,position,_ -> open(position) }
        fun showAppActions(position: Int) {
            val app = filtered.getOrNull(position) ?: return
            val info = app.info.activityInfo
            AlertDialog.Builder(activity).setTitle(app.label)
                .setItems(arrayOf("Uninstall app")) { _, _ ->
                    if (AppActions.uninstall(activity, ComponentName(info.packageName, info.name))) {
                        restoreFocus = false
                        dialog.dismiss()
                    }
                }.setNegativeButton("Cancel", null).show()
        }
        grid.setOnItemLongClickListener { _, _, position, _ -> showAppActions(position); true }
        dialog.setOnKeyListener { _,key,event ->
            when(key) {
                KeyEvent.KEYCODE_BUTTON_Y -> {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && grid.hasFocus())
                        showAppActions(grid.selectedItemPosition)
                    true
                }
                KeyEvent.KEYCODE_BUTTON_B,KeyEvent.KEYCODE_BACK -> { if(event.action==KeyEvent.ACTION_UP && !event.isCanceled) { if(keyboard.visibility==View.VISIBLE)keyboard.hide() else dialog.dismiss() };true }
                KeyEvent.KEYCODE_BUTTON_X -> {
                    if(event.action==KeyEvent.ACTION_UP)keyboard.show()
                    true
                }
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if(event.action==KeyEvent.ACTION_UP && !event.isCanceled) {
                        if(dialog.currentFocus is Button)dialog.currentFocus?.performClick()
                        else if(keyboard.visibility==View.VISIBLE && keyboard.hasFocus())dialog.currentFocus?.performClick()
                        else if(search.hasFocus())keyboard.show() else open(grid.selectedItemPosition.coerceAtLeast(0))
                    };true
                }
                else -> false
            }
        }
        dialog.setOnDismissListener {
            showing.clear()
            if(restoreFocus)DualScreenCoordinator.restoreHostFocus()
        }
        dialog.show()
        FrontendNavigationBar.apply(activity,dialog.window)
        val metrics=activity.resources.displayMetrics
        panel.layoutParams=panel.layoutParams.apply { height=(metrics.heightPixels*0.88f).toInt() }
        dialog.window?.apply {
            setGravity(Gravity.BOTTOM)
            attributes=attributes.apply { y=dp(6) }
            setLayout((metrics.widthPixels*0.94f).toInt(),WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        grid.requestFocus()
        worker.execute {
            val result=runCatching { InstalledApps.load(activity.applicationContext, forceRefresh = true) }
            activity.runOnUiThread {
                if(activity.isDestroyed || !dialog.isShowing)return@runOnUiThread
                result.onSuccess { apps=it;filter() }.onFailure { status.text="Unable to load apps";status.visibility=View.VISIBLE }
            }
        }
    }
}
