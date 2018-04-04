package eu.bkwsu.webcast.wifitranslation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * Created by simonb on 25/09/17.
 */

public class VuMeter {
    private static final float DB_RANGE = 35.0f;
    private static final float RED_RANGE = 2.0f;
    private static final float AMBER_RANGE = 8.0f;

    private static SurfaceHolder holder;

    public VuMeter(SurfaceHolder surfaceHolder) {
        holder = surfaceHolder;
    }

    public static void init () {
        if (holder != null) {
            Canvas canvas = holder.lockCanvas();
            canvas.drawColor(Color.LTGRAY);
            holder.unlockCanvasAndPost(canvas);
        }
    }

    public static void draw (float peakLevel) {
        float levelDb, meterPosition;

        if (holder != null) {
            Canvas canvas = holder.lockCanvas();

            Paint meterColor = new Paint();

            if (canvas != null) {
                float width = canvas.getWidth();
                float height = canvas.getHeight();

                float amberPosition =  width - ((AMBER_RANGE * width) / DB_RANGE);
                float redPosition =  width - ((RED_RANGE * width) / DB_RANGE);

                if (peakLevel > 0.0f) {
                    levelDb = (float) Math.log((double) peakLevel / 32768.0) * 20;
                } else {
                    levelDb = 0.0f - DB_RANGE;
                }
                meterPosition = width + ((levelDb * width) / DB_RANGE);
                if (meterPosition < 0.0f) {
                    meterPosition = 0.0f;
                }

                canvas.drawColor(Color.LTGRAY);

                meterColor.setColor(Color.GREEN);
                canvas.drawRect(0.0f ,height , (meterPosition < amberPosition)?meterPosition:amberPosition, 0, meterColor);
                if (meterPosition > amberPosition) {
                    meterColor.setColor(Color.YELLOW);
                    canvas.drawRect(amberPosition ,height , (meterPosition < redPosition)?meterPosition:redPosition, 0, meterColor);
                }
                if (meterPosition > redPosition) {
                    meterColor.setColor(Color.RED);
                    canvas.drawRect(redPosition ,height , meterPosition, 0, meterColor);
                }

                holder.unlockCanvasAndPost(canvas);
            }
        }
    }

}
