package com.csi.arsample.helper

import android.content.Context
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue

class TapHelper(context : Context) : View.OnTouchListener {
    private var gestureDetector: GestureDetector? = GestureDetector(
        context,
        object : SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // Queue tap if there is space. Tap is lost if queue is full.
                queuedSingleTaps.offer(e)
                return true
            }

            override fun onDown(e: MotionEvent): Boolean {
                return true
            }
        })
    private val queuedSingleTaps: BlockingQueue<MotionEvent> = ArrayBlockingQueue(16)

    /**
     * Polls for a tap.
     *
     * @return if a tap was queued, a MotionEvent for the tap. Otherwise null if no taps are queued.
     */
    fun poll(): MotionEvent? {
        return queuedSingleTaps.poll()
    }

    override fun onTouch(view: View?, motionEvent: MotionEvent?): Boolean {
        return gestureDetector!!.onTouchEvent(motionEvent)
    }
}