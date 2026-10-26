package com.flixtown.tv;

import android.app.Activity;
import android.app.Dialog;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/**
 * "Go to time" for long movies: hours, minutes and seconds as three large fields. Up/Down change the
 * focused field, Left/Right move between fields, number keys type a value, OK (or Go) jumps.
 * The time is kept inside the video (never past 5 seconds before the end).
 */
final class GoToTime {
    private GoToTime(){}
    interface Done{void go(long ms);}

    static Dialog show(Activity a,long currentMs,long durationMs,Done done){
        Dialog dialog=Ui.newDialog(a);
        boolean hours=durationMs>=3_600_000;
        LinearLayout body=Ui.sheet(a,"Go to time","Length "+SeekStep.time(durationMs)+". Up/Down change the value, Left/Right move between fields. Number keys work too.");
        LinearLayout row=Ui.row(a);row.setGravity(Gravity.CENTER_VERTICAL);row.setClipChildren(false);row.setClipToPadding(false);
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-2,-2);rp.topMargin=Ui.dp(a,18);body.addView(row,rp);
        int[] start=SeekStep.split(currentMs);
        final int[] value=hours?new int[]{start[0],start[1],start[2]}:new int[]{start[1]+start[0]*60,start[2]};
        final int[] max=hours?new int[]{(int)(durationMs/3_600_000),59,59}:new int[]{(int)Math.min(599,durationMs/60_000),59};
        String[] names=hours?new String[]{"Hours","Minutes","Seconds"}:new String[]{"Minutes","Seconds"};
        Button[] fields=new Button[value.length];final int[] typed={-1};
        Button go=Ui.primaryButton(a,"Go");Button cancel=Ui.button(a,"Cancel");
        Runnable jump=()->{dialog.dismiss();
            long ms=hours?SeekStep.compose(value[0],value[1],value[2]):SeekStep.compose(0,value[0],value[1]);
            done.go(SeekStep.clamp(ms,durationMs));};
        for(int i=0;i<value.length;i++){final int f=i;
            if(i>0){TextView colon=Ui.heading(a,":",30);colon.setPadding(Ui.dp(a,6),0,Ui.dp(a,6),0);row.addView(colon);}
            LinearLayout col=Ui.column(a);col.setGravity(Gravity.CENTER_HORIZONTAL);col.setClipChildren(false);row.addView(col);
            Button b=Ui.button(a,String.format(Locale.US,"%02d",value[i]));b.setTextSize(30);b.setContentDescription(names[i]);
            col.addView(b,new LinearLayout.LayoutParams(Ui.dp(a,96),Ui.dp(a,72)));
            TextView label=Ui.text(a,names[i],13);label.setTextColor(Ui.TEXT_2);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.topMargin=Ui.dp(a,6);col.addView(label,lp);
            fields[i]=b;
            b.setOnFocusChangeListener((v,has)->{if(has)typed[0]=-1;});
            b.setOnClickListener(v->jump.run());
            b.setOnKeyListener((v,key,e)->{
                if(e.getAction()!=KeyEvent.ACTION_DOWN)return false;
                if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){
                    int d=key==KeyEvent.KEYCODE_DPAD_UP?1:-1;value[f]=(value[f]+d+max[f]+1)%(max[f]+1);typed[0]=-1;
                    b.setText(String.format(Locale.US,"%02d",value[f]));return true;}
                if(key>=KeyEvent.KEYCODE_0&&key<=KeyEvent.KEYCODE_9){int digit=key-KeyEvent.KEYCODE_0;
                    int next=typed[0]<0?digit:typed[0]*10+digit;if(next>max[f])next=digit;
                    value[f]=Math.min(max[f],next);typed[0]=next>=10||next*10>max[f]?-1:next;
                    b.setText(String.format(Locale.US,"%02d",value[f]));
                    if(typed[0]<0 && f+1<fields.length)fields[f+1].requestFocus();
                    return true;}
                if(key==KeyEvent.KEYCODE_DPAD_RIGHT && f==fields.length-1){go.requestFocus();return true;}
                return false;
            });
        }
        LinearLayout buttons=Ui.row(a);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,-2);bp.topMargin=Ui.dp(a,22);body.addView(buttons,bp);
        buttons.addView(go,new LinearLayout.LayoutParams(-2,Ui.dp(a,50)));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,Ui.dp(a,50));cp.leftMargin=Ui.dp(a,10);buttons.addView(cancel,cp);
        go.setMinWidth(Ui.dp(a,120));cancel.setMinWidth(Ui.dp(a,120));
        go.setOnClickListener(v->jump.run());cancel.setOnClickListener(v->dialog.cancel());
        dialog.setContentView(body);
        Ui.showWindow(a,dialog,560,false,0);
        Button first=fields[0];first.post(first::requestFocus);
        return dialog;
    }
}
