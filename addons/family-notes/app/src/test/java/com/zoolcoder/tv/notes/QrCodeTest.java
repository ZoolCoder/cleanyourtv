package com.zoolcoder.tv.notes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class QrCodeTest {
    @Test
    public void matchesAReferenceEncoder() {
        // The same text, level M, byte mode and mask from python-qrcode: version 3, 29 by 29.
        String[] expected = {
            "#######....#.###...#..#######",
            "#.....#..#.#.#####..#.#.....#",
            "#.###.#.##.###.#..###.#.###.#",
            "#.###.#.###...##.##.#.#.###.#",
            "#.###.#.#..#...##..#..#.###.#",
            "#.....#.##..#.........#.....#",
            "#######.#.#.#.#.#.#.#.#######",
            "........##....#.#............",
            "#.#####..#......#####.#####..",
            "##.....##....###.###..#.#...#",
            "#..#.###.#########.....##....",
            "#..###.#####.#.#...#.##.#..#.",
            "..##..#...#...##.#####.#.##..",
            "#####...#.#.#..##.##..#.#.#.#",
            ".#..#.####.......##..##...#..",
            "#.#.##..#.....#.#....#.#...#.",
            ".##.#.#.##..#....##.##....#..",
            "#.#.....#...####...#.##.###.#",
            "#.#.####..##.###..#.#.#..##..",
            "#..#.#.#.##.##..#...###.#..#.",
            "#.#.####..###.#####.#####.###",
            "........#...#......##...#####",
            "#######..##.#..######.#.###..",
            "#.....#.###...###.#.#...#..#.",
            "#.###.#.#.#....####.########.",
            "#.###.#.#..##.#.....##.#...##",
            "#.###.#.###.#.##.####.#.##.#.",
            "#.....#..#...####...##.##..#.",
            "#######.#.#..#.####......##.."
        };
        QrCode code = QrCode.encode("http://192.168.1.50:8765/#123456");
        assertEquals(3, code.version);
        assertEquals(2, code.mask);
        for (int y = 0; y < code.size; y++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < code.size; x++) row.append(code.dark(x, y) ? '#' : '.');
            assertEquals("row " + y, expected[y], row.toString());
        }
    }

    @Test
    public void picksTheSmallestVersionThatFits() {
        assertEquals(1, QrCode.encode("x".repeat(14)).version);
        assertEquals(2, QrCode.encode("x".repeat(15)).version);
        assertEquals(10, QrCode.encode("x".repeat(213)).version);
    }

    @Test
    public void refusesTextLongerThanVersion10() {
        assertNull(QrCode.encode("x".repeat(214)));
    }
}
