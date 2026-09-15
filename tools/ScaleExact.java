// Is float(x*scale) identical to float(2x)*(scale/2) when scale is a power of two?
import java.util.Random;
public class ScaleExact {
  public static void main(String[] a){
    Random r=new Random(9); long bad=0, n=0, sub=0;
    for(int e=0;e<=120;e+=8){
      double scale=Math.pow(2,e), half=scale*0.5;
      for(int i=0;i<400000;i++){
        double x=(r.nextDouble()*4-2);
        float oldWay=(float)(x*scale);
        float newWay=(float)(x*2.0)*(float)half;
        n++;
        if(Float.isInfinite(oldWay)||Float.isInfinite(newWay)||oldWay==0f){sub++;continue;}
        if(Float.floatToRawIntBits(oldWay)!=Float.floatToRawIntBits(newWay)) bad++;
      }
    }
    System.out.printf("%d pairs over scaleExp 0..120, mismatches: %d (%d skipped as inf/zero)%n",n,bad,sub);
  }
}
