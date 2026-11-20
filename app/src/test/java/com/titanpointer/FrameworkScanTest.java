package com.titanpointer;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class FrameworkScanTest {
    @Test
    public void findsMethodUsingTheSwitch() throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("probe.dex")) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
        Diag.clear();
        FrameworkScan.reportDex(o.toByteArray());
        String log = Diag.dump();
        System.out.println(log);
        assertTrue(log, log.contains("string: \"debug.sf.forcescroll_view\""));
        assertTrue(log, log.contains("METHOD x.Probe.prop"));
        assertTrue(log, log.contains("java.lang.System.getProperty"));
        assertTrue(log, log.contains("METHOD x.Probe.onTouch"));
    }
}
