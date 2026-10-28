package assessment;

import java.util.*;

/** Collection-window spread: fixed per-host offsets, bounded by the window, no agent change required. */
public final class ScheduleSpreadTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    public static void main(String[] args) {
        check(AssessmentServer.spreadMinutes(null,22*60,6*60)==15,"default spread is 15 minutes");
        check(AssessmentServer.spreadMinutes("0",0,0)==0,"zero disables the spread");
        check(AssessmentServer.spreadMinutes("abc",0,0)==15,"invalid value falls back to the default");
        check(AssessmentServer.spreadMinutes("1000",0,0)==240,"spread is capped at four hours");
        check(AssessmentServer.spreadMinutes("15",9*60,9*60+20)==10,"spread never exceeds half of a short window");
        check(AssessmentServer.spreadMinutes("15",0,0)==15,"a full-day window spreads normally");
        check(AssessmentServer.spreadMinutes("-5",0,0)==0,"negative disables the spread");
        int start=22*60,spread=15,hosts=1150;int[] bucket=new int[spread];
        for(int i=0;i<hosts;i++){String id="host-"+String.format("%05d",i);int offset=Math.floorMod(id.hashCode(),spread);bucket[offset]++;
            for(int k=0;k<40;k++){boolean held=AssessmentServer.heldForSpread(id,start+k,start,spread);check(held==(k<offset),"host is held exactly until its offset: "+id+" minute+"+k);}
            check(!AssessmentServer.heldForSpread(id,start+spread,start,spread),"every host is released by the end of the spread");
            check(AssessmentServer.heldForSpread(id,start,start,spread)==(offset>0),"only offset zero starts at window open");}
        int min=Integer.MAX_VALUE,max=0;for(int count:bucket){min=Math.min(min,count);max=Math.max(max,count);}
        check(min>=hosts/spread/2&&max<=hosts/spread*2,"offsets spread the fleet evenly (min "+min+", max "+max+")");
        check(!AssessmentServer.heldForSpread("host-00001",5,23*60+50,15),"offsets are measured across midnight");
        check(!AssessmentServer.heldForSpread("any",30,0,0),"spread zero never holds a host");
        System.out.println("ScheduleSpreadTest PASS: "+checks+" checks; "+hosts+" hosts over "+spread+" minutes, bucket sizes "+min+"-"+max+".");
    }
}
