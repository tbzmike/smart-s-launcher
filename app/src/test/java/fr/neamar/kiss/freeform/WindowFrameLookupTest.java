package fr.neamar.kiss.freeform;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WindowFrameLookupTest {
    private static String row(int n, String hash, int user, String name, int task,
                              String type, boolean visible, String frame) {
        return "  Window #" + n + " Window{" + hash + " u" + user + " " + name + "}:\n"
                + "    mDisplayId=0 taskId=" + task + " mSession=Session{}\n"
                + "    mAttrs={(0,0)(fillxfill) ty=" + type + " fmt=OPAQUE}\n"
                + "    mHasSurface=true isReadyForDisplay()=true\n"
                + "    Frames: parent=[0,0][1080,1920] display=[0,0][1080,1920] frame=" + frame + " last=[0,0][0,0]\n"
                + "    isVisible=" + visible + "\n";
    }
    @Test void customTitleIsMatchedByExactTaskAndUserNotName() {
        String dump = row(0, "a123", 0, "Custom window title", 17, "BASE_APPLICATION", true, "[120,240][900,1600]")
                + row(1, "b123", 0, "Custom window title", 18, "BASE_APPLICATION", true, "[0,0][500,600]");
        assertArrayEquals(new int[]{120,240,900,1600}, WindowFrameLookup.find(WindowFrameLookup.parse(dump),17,0).frame);
        assertArrayEquals(new int[]{0,0,500,600}, WindowFrameLookup.find(WindowFrameLookup.parse(dump),18,0).frame);
        assertNull(WindowFrameLookup.find(WindowFrameLookup.parse(dump),17,10));
    }
    @Test void dialogHiddenAndStaleWindowsDoNotBecomeDecorationAnchors() {
        String dump = row(0,"a123",0,"Dialog",17,"APPLICATION_ATTACHED_DIALOG",true,"[300,400][700,800]")
                + row(1,"b123",0,"Invisible",17,"BASE_APPLICATION",false,"[0,0][1080,1920]")
                + row(2,"c123",0,"App",17,"BASE_APPLICATION",true,"[200,300][850,1500]");
        assertArrayEquals(new int[]{200,300,850,1500}, WindowFrameLookup.find(WindowFrameLookup.parse(dump),17,0).frame);
        assertNull(WindowFrameLookup.find(WindowFrameLookup.parse(dump),99,0));
    }
}
