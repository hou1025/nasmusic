import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;

/**
 * 按 Android 端 res/drawable/ic_launcher.xml 的同一套路径数据渲染 Windows 图标。
 * 不依赖任何第三方库，只用 JDK 自带的 AWT。
 */
public final class MakeIcon {

    private static final AffineTransform NOTE_SCALE =
            AffineTransform.getTranslateInstance(54, 54);

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : ".";
        BufferedImage png256 = render(256);
        File pngFile = new File(outDir, "icon.png");
        ImageIO.write(png256, "png", pngFile);
        writeIco(new File(outDir, "icon.ico"), png256);
        System.out.println("written: " + pngFile.getAbsolutePath());
    }

    private static BufferedImage render(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        double s = size / 108.0;
        g.scale(s, s);

        // 底层：深色圆角方形
        g.setColor(new Color(0x1C1C24));
        g.fill(new RoundRectangle2D.Double(0, 0, 108, 108, 28, 28));

        // 黑色外框 + 盘面 + 红底
        fillCircle(g, 54, 54, 34.5, new Color(0x26262F));
        fillCircle(g, 54, 54, 32.7, new Color(0x0D0D14));
        fillCircle(g, 54, 54, 28.5, new Color(0xFD3C55));

        // 白色双音符：整体以 (54,54) 为中心放大 1.096
        Color white = new Color(0xFFFAFA);
        g.setColor(white);

        Path2D beam = new Path2D.Double();
        beam.moveTo(47, 44);
        beam.quadTo(58, 40.5, 69, 40);
        beam.lineTo(69, 48);
        beam.quadTo(58, 48.5, 47, 52);
        beam.closePath();
        g.fill(scaledNote(beam));

        g.fill(scaledNote(new Rectangle2D.Double(47, 44, 3.5, 21.5)));      // 左符干
        g.fill(scaledNote(new Rectangle2D.Double(65.5, 40, 3.5, 22.5)));    // 右符干
        g.fill(scaledNote(new Ellipse2D.Double(37.5, 60.8, 13, 10.4)));     // 左符头
        g.fill(scaledNote(new Ellipse2D.Double(56, 57.6, 13, 10.4)));       // 右符头

        g.dispose();
        return img;
    }

    /** 把音符路径按 1.096 倍围绕中心缩放。 */
    private static Shape scaledNote(Shape shape) {
        AffineTransform t = AffineTransform.getScaleInstance(1.096, 1.096);
        t.translate(-54, -54);
        t.translate(54, 54);
        return t.createTransformedShape(shape);
    }

    private static void fillCircle(Graphics2D g, double cx, double cy, double r, Color color) {
        g.setColor(color);
        g.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
    }

    /** ICO 容器：单个 256x256 PNG 条目（Vista 起支持 PNG 压缩的图标数据）。 */
    private static void writeIco(File file, BufferedImage image) throws IOException {
        ByteArrayOutputStream pngBytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", pngBytes);
        byte[] png = pngBytes.toByteArray();

        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
            // ICONDIR
            writeLE16(out, 0);
            writeLE16(out, 1);
            writeLE16(out, 1);
            // ICONDIRENTRY
            out.writeByte(0);          // 0 表示 256 像素
            out.writeByte(0);
            out.writeByte(0);
            out.writeByte(0);
            writeLE16(out, 1);         // planes
            writeLE16(out, 32);        // bpp
            writeLE32(out, png.length);
            writeLE32(out, 22);        // 数据偏移 = ICONDIR(6) + 条目(16)
            out.write(png);
        }
    }

    private static void writeLE16(DataOutputStream out, int value) throws IOException {
        out.writeByte(value & 0xFF);
        out.writeByte((value >>> 8) & 0xFF);
    }

    private static void writeLE32(DataOutputStream out, int value) throws IOException {
        out.writeByte(value & 0xFF);
        out.writeByte((value >>> 8) & 0xFF);
        out.writeByte((value >>> 16) & 0xFF);
        out.writeByte((value >>> 24) & 0xFF);
    }
}
