/*
 * Copyright 2015 Kaazing Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.aeron.tools.perf_tools;

import java.io.File;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import uk.co.real_logic.aeron.Aeron;
import uk.co.real_logic.aeron.FragmentAssemblyAdapter;
import uk.co.real_logic.aeron.NewConnectionHandler;
import uk.co.real_logic.aeron.Publication;
import uk.co.real_logic.aeron.Subscription;
import uk.co.real_logic.aeron.common.concurrent.logbuffer.BufferClaim;
import uk.co.real_logic.aeron.common.concurrent.logbuffer.Header;
import uk.co.real_logic.agrona.DirectBuffer;
import uk.co.real_logic.agrona.MutableDirectBuffer;
import uk.co.real_logic.agrona.concurrent.UnsafeBuffer;

/**
 * Created by philip on 4/7/15.
 */
public class AeronPing implements NewConnectionHandler
{
    private final int numMsgs = 1000000;
    private final int numWarmupMsgs = 50000;
    private final int msgLen = 32;
    private long[][] timestamps = null;
    private boolean warmedUp = false;
    private Aeron.Context ctx = null;
    private FragmentAssemblyAdapter dataHandler = null;
    private Aeron aeron = null;
    private Publication pub = null;
    private Subscription sub = null;
    private CountDownLatch connectionLatch = null;
    private final int pingStreamId = 10;
    private final int pongStreamId = 11;
    private String pingChannel = "udp://localhost:44444";
    private String pongChannel = "udp://localhost:55555";
    private UnsafeBuffer buffer = null;
    private int msgCount = 0;
    private boolean claim = false;
    private BufferClaim bufferClaim = null;
    private double sorted[] = null;
    private double tmp[] = null;
    private Options options;

    public AeronPing(final String[] args)
    {
        try
        {
            parseArgs(args);
        }
        catch (final Exception e)
        {
            e.printStackTrace();
        }
        ctx = new Aeron.Context()
                .newConnectionHandler(this);
        dataHandler = new FragmentAssemblyAdapter(this::pongHandler);
        aeron = Aeron.connect(ctx);
        pub = aeron.addPublication(pingChannel, pingStreamId);
        sub = aeron.addSubscription(pongChannel, pongStreamId, dataHandler);
        connectionLatch = new CountDownLatch(1);
        buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(msgLen));
        timestamps = new long[2][numMsgs];
        this.claim = claim;
        if (claim)
        {
            bufferClaim = new BufferClaim();
        }
    }

    public void connect()
    {
        try
        {
            connectionLatch.await();
        }
        catch (final Exception e)
        {
            e.printStackTrace();
        }
    }

    public void run()
    {
        for (int i = 0; i < numWarmupMsgs; i++)
        {
            if (!claim)
            {
                sendPingAndReceivePong();
            }
            else
            {
                sendPingAndReceivePongClaim();
            }
        }
        warmedUp = true;

        for (int i = 0; i < numMsgs; i++)
        {
            if (!claim)
            {
                sendPingAndReceivePong();
            }
            else
            {
                sendPingAndReceivePongClaim();
            }
        }

        buffer.putByte(0, (byte) 'q');
        while (pub.offer(buffer, 0, msgLen) <= 0)
        {

        }
    }

    public void shutdown()
    {
        //ctx.close();
        //pub.close();
        //sub.close();
        aeron.close();
    }

    public void generateGraphs()
    {
        double sum = 0.0;
        double max = Double.MIN_VALUE;
        double min = Double.MAX_VALUE;
        int maxIdx = 0;
        int minIdx = 0;
        double mean = 0.0;
        double stdDev = 0.0;

        tmp = new double[timestamps[0].length];
        sorted = new double[tmp.length];

        for (int i = 0; i < tmp.length; i++)
        {
            tmp[i] = (timestamps[1][i] - timestamps[0][i]) / 1000.0;
            if (tmp[i] > max)
            {
                max = tmp[i];
                maxIdx = i;
            }
            if (tmp[i] < min)
            {
                min = tmp[i];
                minIdx = i;
            }
            sum += tmp[i];
        }

        mean = sum / tmp.length;

        System.arraycopy(tmp, 0, sorted, 0, tmp.length);
        Arrays.sort(sorted);

        sum = 0;
        for (int i = 0; i < tmp.length; i++)
        {
            sum += Math.pow(mean - tmp[i], 2);
        }
        stdDev = Math.sqrt(sum / tmp.length);

        generateScatterPlot(min, max, .9);
        generateScatterPlot(min, max, .99);
        generateScatterPlot(min, max, .999);
        generateScatterPlot(min, max, .9999);
        generateScatterPlot(min, max, .99999);
        generateScatterPlot(min, max, .999999);

        System.out.println("Num Messages: " + numMsgs);
        System.out.println("Message Length: " + msgLen);
        System.out.format("Mean: %.3fus\n", mean);
        System.out.format("Standard Deviation: %.3fus\n", stdDev);

        System.out.println("Min: " + min + " Index: " + minIdx);
        System.out.println("Max: " + max + " Index: " + maxIdx);
    }

    private void parseArgs(final String[] args) throws ParseException
    {
        options = new Options();
        options.addOption("c", "claim", false, "Use Try/Claim");
        options.addOption("", "pongChannel", false, "Pong channel");
        options.addOption("", "pingChannel", false, "Ping channel");

        final CommandLineParser parser = new GnuParser();
        final CommandLine command = parser.parse(options, args);

        if (command.hasOption("claim"))
        {
            claim = true;
        }
        else
        {
            claim = false;
        }

        if (command.hasOption("pingChannel"))
        {
            pingChannel = command.getOptionValue("pingChannel", "udp://localhost:44444");
        }

        if (command.hasOption("pongChannel"))
        {
            pongChannel = command.getOptionValue("pongChannel", "udp://localhost:55555");
        }
    }

    private void generateScatterPlot(final double min, final double max, final double percentile)
    {
        final int width = 390;
        final int height = 370;
        final int num = (int)((numMsgs - 1) * percentile);
        final double newMax = sorted[num];

        final File file = new File(percentile + "_percentile.dat");
        try
        {
            final PrintWriter out = new PrintWriter(file);
            for (int i = 0; i < numMsgs; i++)
            {
                if (tmp[i] <= newMax)
                {
                    out.println(i + "\t" + tmp[i]);
                }
            }
            out.close();
        }
        catch (final Exception e)
        {
            e.printStackTrace();
        }
    }

    public void dumpData()
    {
        final File file = new File("ping.dat");
        try
        {
            final PrintWriter out = new PrintWriter(file);
            for (int i = 0; i < numMsgs; i++)
            {
                out.println(i + "\t" + tmp[i]);
            }
            out.close();
        }
        catch (final Exception e)
        {
            e.printStackTrace();
        }
    }

    @Override
    public void onNewConnection(final String channel, final int streamId,
                                   final int sessionId, final long position, final String sourceInfo)
    {
        if (channel.equals(pongChannel) && pongStreamId == streamId)
        {
            connectionLatch.countDown();
        }
    }

    private void pongHandler(final DirectBuffer buffer, final int offset, final int length,
                             final Header header)
    {
        if (buffer.getByte(offset + 0) == (byte)'p')
        {
            timestamps[1][buffer.getInt(offset + 1)] = System.nanoTime();
        }
    }

    private void sendPingAndReceivePong()
    {
        if (!warmedUp)
        {
            buffer.putByte(0, (byte) 'w');
        }
        else
        {
            buffer.putByte(0, (byte)'p');
            buffer.putInt(1, msgCount);
            timestamps[0][msgCount++] = System.nanoTime();
        }
        while (pub.offer(buffer, 0, msgLen) <= 0)
        {

        }

        while (sub.poll(1) <= 0)
        {

        }
    }

    private void sendPingAndReceivePongClaim()
    {
        if (pub.tryClaim(msgLen, bufferClaim) > 0)
        {
            try
            {
                final MutableDirectBuffer buffer = bufferClaim.buffer();
                final int offset = bufferClaim.offset();
                if (!warmedUp)
                {
                    buffer.putByte(offset + 0, (byte) 'w');
                }
                else
                {
                    buffer.putByte(offset + 0, (byte) 'p');
                    buffer.putInt(offset + 1, msgCount);
                    timestamps[0][msgCount++] = System.nanoTime();
                }
            }
            catch (final Exception e)
            {
                e.printStackTrace();
            }
            finally
            {
                bufferClaim.commit();
                while (sub.poll(1) <= 0)
                {

                }
            }
        }
        else
        {
            sendPingAndReceivePongClaim();
        }
    }

    private double round(final double unrounded, final int precision, final int roundingMode)
    {
        final BigDecimal bd = new BigDecimal(unrounded);
        final BigDecimal rounded = bd.setScale(precision, roundingMode);
        return rounded.doubleValue();
    }

    public static void main(final String[] args)
    {
        final AeronPing ping = new AeronPing(args);

        ping.connect();
        ping.run();
        ping.shutdown();
        ping.generateGraphs();
        ping.dumpData();
    }
}
