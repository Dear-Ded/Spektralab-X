// Independent second-host test: JDK CMM only, no production Kotlin helpers.
// Co-created with GPT (OpenAI), 2026-11-11.
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ProfileCmm {
    public static void main(String[] args) throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of(args[0]));
        if (args.length > 1 && args[1].equals("reject")) {
            try { new ICC_ColorSpace(ICC_Profile.getInstance(bytes)).toCIEXYZ(new float[]{.5f,.5f,.5f}); }
            catch (IllegalArgumentException | java.awt.color.CMMException expected) {
                System.out.println("PASS independent CMM rejected broken baseline: " + expected); return;
            }
            throw new AssertionError("baseline was unexpectedly usable");
        }
        ICC_ColorSpace cmm = new ICC_ColorSpace(ICC_Profile.getInstance(bytes));
        double[][] m = {{.7976749,.1351917,.0313534},{.2880402,.7118741,.0000857},{0,0,.8252100}};
        double maxError = 0;
        for (int i=0;i<=1024;i++) for (int channel=-1;channel<3;channel++) {
            float v = i / 1024f;
            float[] rgb = new float[3];
            for (int c=0;c<3;c++) rgb[c] = channel<0 || c==channel ? v : 0;
            float[] xyz = cmm.toCIEXYZ(rgb);
            for (int row=0;row<3;row++) {
                double expected = 0;
                for (int c=0;c<3;c++) expected += m[row][c] * (rgb[c]<1.0/32 ? rgb[c]/16.0 : Math.pow(rgb[c],1.8));
                maxError = Math.max(maxError,Math.abs(xyz[row]-expected));
            }
        }
        if (maxError>.0005) throw new AssertionError("CMM maximum XYZ error=" + maxError);
        System.out.println("PASS independent JDK CMM: 4100 RGB samples; max XYZ error=" + maxError);
    }
}
