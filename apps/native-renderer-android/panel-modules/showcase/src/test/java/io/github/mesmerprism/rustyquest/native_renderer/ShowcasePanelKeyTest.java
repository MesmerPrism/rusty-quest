package io.github.mesmerprism.rustyquest.native_renderer;

/** Runs the production nested counter without constructing an Android Activity. */
public final class ShowcasePanelKeyTest {
    private static int checks;
    private static void check(boolean condition,String reason){checks++;if(!condition)throw new AssertionError(reason);}
    private static boolean press(ShowcasePanelModule.TripleBSequence sequence,long down,long up){sequence.down(down,down,0);return sequence.up(up,down);}
    public static void main(String[]args){
        ShowcasePanelModule.TripleBSequence sequence=new ShowcasePanelModule.TripleBSequence(5000);
        check(!sequence.up(0,0),"opening orphan key-up counted");
        check(!press(sequence,10,20),"first completed press closed panel");
        check(!press(sequence,30,40),"second completed press closed panel");
        sequence.down(50,50,0);check(sequence.up(60,50),"third completed press did not close");
        check(!sequence.up(61,50),"duplicate key-up closed again");
        sequence.down(70,70,0);sequence.down(71,70,1);sequence.down(72,70,2);sequence.down(73,70,0);
        check(!sequence.up(80,70),"repeat/duplicate down advanced count");
        check(!press(sequence,90,100),"repeat/duplicate down counted extra press");
        check(press(sequence,110,120),"normal third press after repeats missing");
        sequence.reset();check(!press(sequence,0,10),"first boundary press");check(!press(sequence,100,110),"second boundary press");check(press(sequence,4990,5000),"inclusive native window boundary rejected");
        sequence.reset();press(sequence,0,10);press(sequence,100,110);check(!press(sequence,4990,5001),"out-of-window completed press accepted");
        check(!press(sequence,5010,5020),"expired sequence did not reset");check(!press(sequence,5030,5040),"expired sequence retained prior presses");check(press(sequence,5050,5060),"new sequence after expiry unavailable");
        sequence.reset();press(sequence,0,10);press(sequence,20,30);sequence.reset();check(!sequence.up(40,20),"focus reset retained paired down");check(!press(sequence,50,60),"lifecycle reset retained count");check(!press(sequence,70,80),"lifecycle reset retained second count");check(press(sequence,90,100),"new focused sequence unavailable");
        sequence.reset();press(sequence,100,110);sequence.down(120,120,0);check(!sequence.up(130,119),"mismatched up counted");check(!press(sequence,140,150),"mismatched up retained count");check(!press(sequence,160,170),"mismatched up retained second count");check(press(sequence,180,190),"sequence after mismatch unavailable");
        sequence.reset();press(sequence,100,110);sequence.down(90,90,0);check(!sequence.up(95,90),"backwards clock accepted");check(!press(sequence,120,130),"backwards clock retained count");
        sequence.reset();sequence.down(0,0,1);check(!sequence.up(10,0),"unpaired repeat created a press");
        sequence=new ShowcasePanelModule.TripleBSequence(250);press(sequence,0,10);press(sequence,100,110);check(press(sequence,240,250),"configured native window not used");
        try{new ShowcasePanelModule.TripleBSequence(249);throw new AssertionError("undersized window");}catch(IllegalArgumentException expected){checks++;}
        try{new ShowcasePanelModule.TripleBSequence(30001);throw new AssertionError("oversized window");}catch(IllegalArgumentException expected){checks++;}
        System.out.println("PASS "+checks+" production triple-B paired-press checks: orphan/repeat/mismatch/expiry/reset/clock/bounds");
    }
}
