import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import javax.imageio.ImageIO;

/**
 * 从 maplestory.io 拉取 BOSS 精灵图，裁剪透明边并按指定尺寸缩放后保存为小图标。
 * 用法: java SpriteIcon.java <输出目录> <名称:mobId>...
 */
public class SpriteIcon {

    public static void main(String[] args) throws Exception {
        String outDir = args[0];
        File dir = new File(outDir);
        dir.mkdirs();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        for (int i = 1; i < args.length; i++) {
            String[] parts = args[i].split(":");
            String name = parts[0];
            String mobId = parts[1];
            int size = parts.length > 2 ? Integer.parseInt(parts[2]) : 96;
            String url = "https://maplestory.io/api/GMS/255/mob/" + mobId + "/icon";
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "gmsr-scheduler/1.0")
                        .build();
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    System.out.println("FAIL " + name + " HTTP " + response.statusCode());
                    continue;
                }
                BufferedImage raw = ImageIO.read(new java.io.ByteArrayInputStream(response.body()));
                if (raw == null) {
                    System.out.println("FAIL " + name + " 不是有效图片");
                    continue;
                }
                BufferedImage trimmed = trim(raw);
                BufferedImage scaled = scale(trimmed, size);
                File out = new File(dir, name + ".png");
                ImageIO.write(scaled, "png", out);
                System.out.printf("OK   %-14s %dx%d -> %dx%d  %d KB%n",
                        name, raw.getWidth(), raw.getHeight(), scaled.getWidth(), scaled.getHeight(), out.length() / 1024);
            } catch (Exception ex) {
                System.out.println("FAIL " + name + " " + ex.getMessage());
            }
        }
    }

    /** 去掉四周全透明的空白，让图标主体更大。 */
    private static BufferedImage trim(BufferedImage image) {
        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) > 8) {
                    if (x < minX) {
                        minX = x;
                    }
                    if (y < minY) {
                        minY = y;
                    }
                    if (x > maxX) {
                        maxX = x;
                    }
                    if (y > maxY) {
                        maxY = y;
                    }
                }
            }
        }
        if (maxX < 0) {
            return image;
        }
        int pad = Math.max(2, Math.min(maxX - minX, maxY - minY) / 20);
        minX = Math.max(0, minX - pad);
        minY = Math.max(0, minY - pad);
        maxX = Math.min(image.getWidth() - 1, maxX + pad);
        maxY = Math.min(image.getHeight() - 1, maxY + pad);
        return image.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    /** 等比缩放到边长 size 的方形画布内。 */
    private static BufferedImage scale(BufferedImage source, int size) {
        int width = source.getWidth();
        int height = source.getHeight();
        double ratio = Math.min((double) size / width, (double) size / height);
        int targetW = Math.max(1, (int) Math.round(width * ratio));
        int targetH = Math.max(1, (int) Math.round(height * ratio));
        BufferedImage target = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, targetW, targetH, null);
        g.dispose();
        return target;
    }
}
