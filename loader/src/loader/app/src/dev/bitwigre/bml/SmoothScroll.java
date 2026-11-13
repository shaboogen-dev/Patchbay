package dev.bitwigre.bml;

import java.awt.*;
import java.awt.event.MouseWheelEvent;
import javax.swing.*;

/**
 * Wheel scrolling that glides instead of jumping. Each wheel notch (or a precise touchpad's
 * fraction of one) moves a target position; the view eases out towards it at the display's
 * refresh rate. A notch that arrives while it moves extends the target from where the view is,
 * so fast wheeling never restarts or stutters. The viewport copies pixels for each step (blit)
 * rather than repainting the list. Dragging the scrollbar and the keyboard stay direct.
 */
final class SmoothScroll {
    private static final int DURATION_MS=140;
    private final JScrollPane pane;
    private final javax.swing.Timer timer;
    private double from,target;
    private long started;

    private SmoothScroll(JScrollPane pane){
        this.pane=pane;
        timer=new javax.swing.Timer(16,e->step());
        timer.setCoalesce(true);
    }

    /** Takes over the pane's wheel scrolling. */
    static void install(JScrollPane pane){
        SmoothScroll s=new SmoothScroll(pane);
        pane.setWheelScrollingEnabled(false);
        pane.getViewport().setScrollMode(JViewport.BLIT_SCROLL_MODE);
        pane.addMouseWheelListener(s::wheel);
        // Anything else that moves the view (scrollbar drag, keys, code) ends the glide there.
        pane.getVerticalScrollBar().getModel().addChangeListener(e->{
            if(s.timer.isRunning()&&Math.abs(s.position()-s.current())>1.5)s.timer.stop();
        });
    }

    /** One animation frame per refresh of the screen the component is on: a 165 Hz screen gets
     *  6 ms frames, where a fixed 16 ms timer judders. 60 Hz when the rate is unknown. */
    static int frameMillis(Component c){
        GraphicsConfiguration gc=c==null?null:c.getGraphicsConfiguration();
        int hz=0;
        try{hz=gc==null?0:gc.getDevice().getDisplayMode().getRefreshRate();}catch(RuntimeException ignored){}
        if(hz<30)hz=60;
        return Math.max(1,(int)Math.round(1000.0/hz));
    }

    private void wheel(MouseWheelEvent e){
        if(e.isShiftDown()||e.getScrollType()!=MouseWheelEvent.WHEEL_UNIT_SCROLL)return;
        JScrollBar bar=pane.getVerticalScrollBar();
        if(!bar.isVisible()&&pane.getVerticalScrollBarPolicy()!=ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS)return;
        double rotation=e.getPreciseWheelRotation();
        double step=rotation*e.getScrollAmount()*bar.getUnitIncrement(rotation<0?-1:1);
        double base=timer.isRunning()?target:position();
        target=clamp(base+step);
        from=position();
        if(Math.abs(target-from)<.5)return;
        started=System.nanoTime();
        timer.setDelay(frameMillis(pane));
        if(!timer.isRunning())timer.start();
        e.consume();
    }

    private void step(){
        double t=Math.min(1,(System.nanoTime()-started)/1e6/DURATION_MS);
        double eased=1-Math.pow(1-t,3);
        move(from+(target-from)*eased);
        if(t>=1)timer.stop();
    }

    // The position the glide last set, kept fractional so slow touchpad steps add up.
    private double current;
    private double current(){return current;}
    private double position(){return pane.getViewport().getViewPosition().y;}
    private void move(double y){
        current=y;
        JViewport v=pane.getViewport();
        Point p=v.getViewPosition();
        int to=(int)Math.round(y);
        if(to!=p.y)v.setViewPosition(new Point(p.x,to));
    }
    private double clamp(double y){
        JViewport v=pane.getViewport();
        int max=Math.max(0,v.getViewSize().height-v.getExtentSize().height);
        return Math.max(0,Math.min(max,y));
    }
}
