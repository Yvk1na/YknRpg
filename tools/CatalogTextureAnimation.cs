using System;
using System.Drawing;
using System.Drawing.Imaging;

// Compiles AI-authored effect cells into Minecraft's native texture strip.
// The original sprite's alpha is invariant in every exported frame.
public static class CatalogTextureAnimation
{
    public static void Build(string spritePath, string effectPath, string handPath, string iconPath,
        string effect, double strength, int offsetX, int offsetY)
    {
        const int size = 64;
        using (var sprite = new Bitmap(spritePath))
        using (var sheet = new Bitmap(effectPath))
        using (var output = new Bitmap(size, size * 16, PixelFormat.Format32bppArgb))
        {
            if (sprite.Width != size || sprite.Height != size)
                throw new InvalidOperationException("Unexpected source dimensions: " + spritePath);
            // Each cell can have extra horizontal or vertical canvas padding.
            // Sample its centered square so the authored marks keep their shape.
            if (sheet.Width < 256 || sheet.Height < 256 ||
                Math.Max(sheet.Width, sheet.Height) > Math.Min(sheet.Width, sheet.Height) * 3)
                throw new InvalidOperationException("Expected a 4x4 effect sheet: " + effectPath);
            double cellWidth = sheet.Width / 4.0;
            double cellHeight = sheet.Height / 4.0;
            double cell = Math.Min(cellWidth, cellHeight);
            double insetX = (cellWidth - cell) / 2;
            double insetY = (cellHeight - cell) / 2;
            int step = sheet.Width == 256 && sheet.Height == 256 ? 1 : 8;
            int minimumTransparent = sheet.Width * sheet.Height / (step * step * 4);
            int transparent = 0;
            for (int sy = 0; sy < sheet.Height && transparent < minimumTransparent; sy += step)
                for (int sx = 0; sx < sheet.Width; sx += step)
                    if (sheet.GetPixel(sx, sy).A < 16) transparent++;
            if (transparent < minimumTransparent)
                throw new InvalidOperationException("Effect background is not transparent: " + effectPath);
            var anchors = new double[size * size];
            for (int frame = 0; frame < 16; frame++)
                for (int y = 0; y < size; y++)
                    for (int x = 0; x < size; x++)
                    {
                        Color mark = SampleEffect(sheet, (frame % 4) * cellWidth + insetX,
                            (frame / 4) * cellHeight + insetY, cell, x - offsetX, y - offsetY);
                        if (mark.A < 96) continue;
                        double light = Math.Max(mark.R, Math.Max(mark.G, mark.B)) / 255.0 * mark.A / 255.0;
                        anchors[y * size + x] = Math.Max(anchors[y * size + x], light);
                    }
            for (int frame = 0; frame < 16; frame++)
            {
                double x0 = (frame % 4) * cellWidth + insetX;
                double y0 = (frame / 4) * cellHeight + insetY;
                for (int y = 0; y < size; y++)
                {
                    for (int x = 0; x < size; x++)
                    {
                        Color original = sprite.GetPixel(x, y);
                        if (original.A == 0) continue;
                        Color light = SampleEffect(sheet, x0, y0, cell, x - offsetX, y - offsetY);
                        // Ignore translucent fringe noise from the authored overlays.
                        double coverage = light.A < 96 ? 0 : light.A / 255.0;
                        double amount = coverage * strength * Affinity(original, effect);
                        double dim = anchors[y * size + x] * strength * Affinity(original, effect) * 0.22;
                        output.SetPixel(x, frame * size + y, Color.FromArgb(original.A,
                            Lit(original.R, light.R, amount, dim), Lit(original.G, light.G, amount, dim),
                            Lit(original.B, light.B, amount, dim)));
                    }
                }
            }
            if (!String.IsNullOrEmpty(handPath)) output.Save(handPath, ImageFormat.Png);
            if (!String.IsNullOrEmpty(iconPath)) BuildIcon(sprite, output, iconPath);
        }
    }

    private static Color SampleEffect(Bitmap sheet, double x0, double y0, double cell, int x, int y)
    {
        if (x < 0 || x >= 64 || y < 0 || y >= 64) return Color.Transparent;
        return sheet.GetPixel((int)(x0 + (x + 0.5) * cell / 64), (int)(y0 + (y + 0.5) * cell / 64));
    }

    // Fit the visible object, not the source canvas, into a 30-pixel square.
    // All phases share this crop, the same solid silhouette and the same base colours.
    private static void BuildIcon(Bitmap sprite, Bitmap animation, string path)
    {
        const int size = 32;
        int left = 64, top = 64, right = 0, bottom = 0;
        for (int y = 0; y < 64; y++)
            for (int x = 0; x < 64; x++)
                if (sprite.GetPixel(x, y).A >= 128)
                {
                    left = Math.Min(left, x); right = Math.Max(right, x + 1);
                    top = Math.Min(top, y); bottom = Math.Max(bottom, y + 1);
                }
        if (left >= right || top >= bottom) throw new InvalidOperationException("Empty sprite: " + path);
        double step = Math.Max(right - left, bottom - top) / 30.0;
        using (var icon = new Bitmap(size, size * 16, PixelFormat.Format32bppArgb))
        {
            for (int y = 1; y < size - 1; y++)
                for (int x = 1; x < size - 1; x++)
                {
                    double x0 = (left + right) * 0.5 + (x - 16) * step;
                    double y0 = (top + bottom) * 0.5 + (y - 16) * step;
                    double weight = 0;
                    var rgb = new double[3];
                    var mean = new double[16, 3];
                    var peak = new double[16, 3];
                    var peakLight = new double[16];
                    for (int sy = Math.Max(top, (int)Math.Floor(y0)); sy < Math.Min(bottom, y0 + step); sy++)
                        for (int sx = Math.Max(left, (int)Math.Floor(x0)); sx < Math.Min(right, x0 + step); sx++)
                        {
                            Color basis = sprite.GetPixel(sx, sy);
                            if (basis.A == 0) continue;
                            double area = (Math.Min(x0 + step, sx + 1) - Math.Max(x0, sx))
                                * (Math.Min(y0 + step, sy + 1) - Math.Max(y0, sy)) * basis.A / 255.0;
                            weight += area;
                            rgb[0] += area * basis.R; rgb[1] += area * basis.G; rgb[2] += area * basis.B;
                            for (int f = 0; f < 16; f++)
                            {
                                Color lit = animation.GetPixel(sx, f * 64 + sy);
                                int dr = lit.R - basis.R, dg = lit.G - basis.G, db = lit.B - basis.B;
                                mean[f, 0] += area * dr; mean[f, 1] += area * dg; mean[f, 2] += area * db;
                                // Retain a narrow moving glint even when several source pixels share one texel.
                                double light = area * Math.Max(dr, Math.Max(dg, db));
                                if (light <= peakLight[f]) continue;
                                peakLight[f] = light;
                                peak[f, 0] = area * dr; peak[f, 1] = area * dg; peak[f, 2] = area * db;
                            }
                        }
                    if (weight < step * step * 0.26) continue;
                    for (int c = 0; c < 3; c++)
                        rgb[c] = 6 * Math.Round(((rgb[c] / weight - 80) * 1.06 + 80) / 6);
                    for (int f = 0; f < 16; f++)
                    {
                        var channels = new int[3];
                        for (int c = 0; c < 3; c++)
                        {
                            double delta = mean[f, c] / weight;
                            if (peakLight[f] > 0) delta += 0.55 * Math.Max(0, peak[f, c] - delta);
                            channels[c] = Math.Max(0, Math.Min(255, (int)Math.Round(rgb[c] + delta)));
                        }
                        icon.SetPixel(x, f * size + y, Color.FromArgb(255, channels[0], channels[1], channels[2]));
                    }
                }
            LiftSmallHighlights(icon);
            icon.Save(path, ImageFormat.Png);
        }
    }

    // Boost only motion already present in the authored strip. Four bright texels
    // give thin metal edges a readable accent without letting one outlier set the gain.
    private static void LiftSmallHighlights(Bitmap icon)
    {
        int size = icon.Width;
        var minima = new int[size * size, 3];
        var maxima = new int[size * size, 3];
        var spans = new int[size * size];
        for (int i = 0; i < spans.Length; i++)
        {
            for (int c = 0; c < 3; c++) minima[i, c] = 255;
            for (int f = 0; f < 16; f++)
            {
                int rgb = icon.GetPixel(i % size, i / size + f * size).ToArgb();
                for (int c = 0; c < 3; c++)
                {
                    int value = (rgb >> (16 - c * 8)) & 255;
                    minima[i, c] = Math.Min(minima[i, c], value);
                    maxima[i, c] = Math.Max(maxima[i, c], value);
                    spans[i] = Math.Max(spans[i], maxima[i, c] - minima[i, c]);
                }
            }
        }
        Array.Sort(spans);
        double gain = Math.Min(6, 24.0 / Math.Max(1, spans[spans.Length - 4]));
        gain = Math.Min(gain, 84.0 / Math.Max(1, spans[spans.Length - 1]));
        if (gain <= 1) return;
        for (int i = 0; i < spans.Length; i++)
            for (int f = 0; f < 16; f++)
            {
                int x = i % size, y = i / size + f * size;
                Color pixel = icon.GetPixel(x, y);
                if (pixel.A == 0) continue;
                int rgb = pixel.ToArgb();
                var channels = new int[3];
                for (int c = 0; c < 3; c++)
                {
                    int value = (rgb >> (16 - c * 8)) & 255;
                    double range = gain * (maxima[i, c] - minima[i, c]);
                    double baseline = Math.Max(0, Math.Min(minima[i, c], 255 - range));
                    channels[c] = Math.Min(255, (int)Math.Round(baseline + gain * (value - minima[i, c])));
                }
                icon.SetPixel(x, y, Color.FromArgb(255, channels[0], channels[1], channels[2]));
            }
    }

    private static int Lit(int source, int light, double amount, double dim)
    {
        double baseline = source * (1 - dim);
        return Math.Min(255, (int)Math.Round(baseline + (255 - baseline) * light / 255.0 * amount));
    }

    private static double Affinity(Color color, string effect)
    {
        // Each item's artwork now defines its own colored mask and trajectory.
        double minimum = effect == "sheen" ? 0.2 : 0.35;
        double brightness = Math.Max(color.R, Math.Max(color.G, color.B)) / 255.0;
        return minimum + (1 - minimum) * brightness;
    }
}
