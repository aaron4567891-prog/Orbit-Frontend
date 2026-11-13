package au.orbit.frontend

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView

/** Rearranges the existing media views without detaching a playing video surface. */
internal class GameMediaGrid(context:Context,private val images:Map<String,ImageView>,private val video:View):ViewGroup(context) {
    private var shown=emptyList<String>()
    private var slots=emptyList<List<View>>()
    private val gap=(4*resources.displayMetrics.density).toInt()
    init {
        images.values.forEach { addView(it) };addView(video)
        (images.values+video).forEach { it.visibility=GONE }
    }
    fun show(types:List<String>) {
        if(shown==types)return
        shown=types.toList()
        val combined="covers" in types && "videos" in types
        slots=types.filterNot { combined && it=="covers" }.map { type ->
            when {
                type=="videos" && combined -> listOf(images.getValue("covers"),video)
                type=="videos" -> listOf(video)
                else -> listOf(images.getValue(type))
            }
        }
        images.forEach { (type,image) ->
            if(type !in types)image.visibility=GONE
            else if(image.visibility==GONE)image.visibility=VISIBLE
        }
        video.visibility=if("videos" in types)VISIBLE else GONE
        requestLayout()
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val width=MeasureSpec.getSize(widthMeasureSpec);val height=MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width,height)
        val columns=if(slots.size<=1)1 else 2
        val rows=((slots.size+columns-1)/columns).coerceAtLeast(1)
        val childWidth=(width/columns-gap*2).coerceAtLeast(0)
        val childHeight=(height/rows-gap*2).coerceAtLeast(0)
        slots.flatten().forEach { it.measure(MeasureSpec.makeMeasureSpec(childWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(childHeight,MeasureSpec.EXACTLY)) }
    }
    override fun onLayout(changed:Boolean,left:Int,top:Int,right:Int,bottom:Int) {
        val columns=if(slots.size<=1)1 else 2
        val rows=((slots.size+columns-1)/columns).coerceAtLeast(1)
        slots.forEachIndexed { index,views ->
            val x=(index%columns)*width/columns+gap
            val y=(index/columns)*height/rows+gap
            views.forEach { it.layout(x,y,x+it.measuredWidth,y+it.measuredHeight) }
        }
    }
}
