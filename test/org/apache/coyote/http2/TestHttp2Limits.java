/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.coyote.http2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;

import org.apache.coyote.http2.HpackEncoder.State;
import org.apache.tomcat.util.http.MimeHeaders;

public class TestHttp2Limits extends Http2TestBase {

    @Test
    public void testHeaderLimits1x128() throws Exception {
        // Well within limits
        doTestHeaderLimits(1, 128, 0);
    }


    @Test
    public void testHeaderLimits100x32() throws Exception {
        // Just within default maxHeaderCount
        // Note request has 3 standard headers
        doTestHeaderLimits(97, 32, 0);
    }


    @Test
    public void testHeaderLimits101x32() throws Exception {
        // Just above default maxHeaderCount
        doTestHeaderLimits(98, 32, 1);
    }


    @Test
    public void testHeaderLimits20x32WithLimit10() throws Exception {
        // Check lower count limit is enforced
        doTestHeaderLimits(20, 32, -1, 10, Constants.DEFAULT_MAX_HEADER_SIZE, 0, 1);
    }


    @Test
    public void testHeaderLimits8x1001() throws Exception {
        // Just within default maxHttpHeaderSize
        // per header overhead plus standard 2 headers
        doTestHeaderLimits(8, 1001, 0);
    }


    @Test
    public void testHeaderLimits8x1002() throws Exception {
        // Just above default maxHttpHeaderSize
        doTestHeaderLimits(8, 1002, 1);
    }


    @Test
    public void testHeaderLimits3x1024WithLimit2048() throws Exception {
        // Check lower size limit is enforced
        doTestHeaderLimits(3, 1024, -1, Constants.DEFAULT_MAX_HEADER_COUNT, 2 * 1024, 0, 1);
    }


    @Test
    public void testHeaderLimits1x12k() throws Exception {
        // Bug 60232
        doTestHeaderLimits(1, 12*1024, 1);
    }


    @Test
    public void testHeaderLimits1x12kin1kChunks() throws Exception {
        // Bug 60232
        doTestHeaderLimits(1, 12*1024, 1024, 1);
    }


    @Test
    public void testHeaderLimits1x12kin1kChunksThenNewRequest() throws Exception {
        // Bug 60232
        doTestHeaderLimits(1, 12*1024, 1024, 1);

        output.clearTrace();
        sendSimpleGetRequest(5);
        parser.readFrame(true);
        parser.readFrame(true);
        Assert.assertEquals(getSimpleResponseTrace(5), output.getTrace());
    }


    @Test
    public void testHeaderLimits1x32k() throws Exception {
        // Bug 60232
        doTestHeaderLimits(1, 32*1024, 1);
    }


    @Test
    public void testHeaderLimits1x32kin1kChunks() throws Exception {
        // Bug 60232
        // 500ms per frame write delay to give server a chance to process the
        // stream reset and the connection reset before the request is fully
        // sent.
        doTestHeaderLimits(1, 32*1024, 1024, 500, 2);
    }


    @Test
    public void testHeaderLimits1x128k() throws Exception {
        // Bug 60232
        doTestHeaderLimits(1, 128*1024, 2);
    }


    @Test
    public void testHeaderLimits1x512k() throws Exception {
        // Bug 60232
        doTestHeaderLimits(1, 512*1024, 2);
    }


    @Test
    public void testHeaderLimits10x512k() throws Exception {
        // Bug 60232
        doTestHeaderLimits(10, 512*1024, 2);
    }


    private void doTestHeaderLimits(int headerCount, int headerSize, int failMode) throws Exception {
        doTestHeaderLimits(headerCount, headerSize, -1, failMode);
    }


    private void doTestHeaderLimits(int headerCount, int headerSize, int maxHeaderPayloadSize,
            int failMode) throws Exception {
        doTestHeaderLimits(headerCount, headerSize, maxHeaderPayloadSize, 0, failMode);
    }


    private void doTestHeaderLimits(int headerCount, int headerSize, int maxHeaderPayloadSize,
            int delayms, int failMode) throws Exception {
        doTestHeaderLimits(headerCount, headerSize, maxHeaderPayloadSize,
                Constants.DEFAULT_MAX_HEADER_COUNT, Constants.DEFAULT_MAX_HEADER_SIZE, delayms,
                failMode);
    }


    private void doTestHeaderLimits(int headerCount, int headerSize, int maxHeaderPayloadSize,
            int maxHeaderCount, int maxHeaderSize, int delayms, int failMode) throws Exception {

        // Build the custom headers
        Map<String,String> customHeaders = new HashMap<>();
        StringBuilder headerValue = new StringBuilder(headerSize);
        // Does not need to be secure
        Random r = new Random();
        for (int i = 0; i < headerSize; i++) {
            // Random lower case characters
            headerValue.append((char) ('a' + r.nextInt(26)));
        }
        String v = headerValue.toString();
        for (int i = 0; i < headerCount; i++) {
            customHeaders.put("X-TomcatTest" + i, v);
        }

        enableHttp2();

        Http2Protocol http2Protocol =
                (Http2Protocol) getTomcatInstance().getConnector().findUpgradeProtocols()[0];
        http2Protocol.setMaxHeaderCount(maxHeaderCount);
        http2Protocol.setMaxHeaderSize(maxHeaderSize);

        configureAndStartWebApplication();
        openClientConnection();
        doHttpUpgrade();
        sendClientPreface();
        validateHttp2InitialResponse();

        if (maxHeaderPayloadSize == -1) {
            maxHeaderPayloadSize = output.getMaxFrameSize();
        }

        // Build the simple request
        byte[] frameHeader = new byte[9];
        // Assumes at least one custom header and that all headers are the same
        // length. These assumptions are valid for these tests.
        ByteBuffer headersPayload = ByteBuffer.allocate(200 + (int) (customHeaders.size() *
                customHeaders.values().iterator().next().length() * 1.2));

        populateHeadersPayload(headersPayload, customHeaders);

        Exception e = null;
        try {
            int written = 0;
            int left = headersPayload.limit() - written;
            while (left > 0) {
                int thisTime = Math.min(left, maxHeaderPayloadSize);
                populateFrameHeader(frameHeader, written, left, thisTime, 3);
                writeFrame(frameHeader, headersPayload, headersPayload.limit() - left,
                        thisTime, delayms);
                left -= thisTime;
                written += thisTime;
            }
        } catch (IOException ioe) {
            e = ioe;
        }

        switch (failMode) {
        case 0: {
            // Expect a normal response
            readSimpleGetResponse();
            Assert.assertEquals(getSimpleResponseTrace(3), output.getTrace());
            Assert.assertNull(e);
            break;
        }
        case 1: {
            // Expect a stream reset
            parser.readFrame(true);
            Assert.assertEquals("3-RST-[11]\n", output.getTrace());
            Assert.assertNull(e);
            break;
        }
        case 2: {
            // Behaviour depends on timing. If reset is processed fast enough,
            // frames will be swallowed before the connection reset limit is
            // reached
            if (e == null) {
                parser.readFrame(true);
                Assert.assertEquals("3-RST-[11]\n", output.getTrace());
                Assert.assertNull(e);
            }
            // Else is non-null as expected for a connection reset
            break;
        }
        default: {
            Assert.fail("Unknown failure mode");
        }
        }
    }


    private void populateHeadersPayload(ByteBuffer headersPayload, Map<String,String> customHeaders)
            throws Exception {
        MimeHeaders headers = new MimeHeaders();
        headers.addValue(":method").setString("GET");
        headers.addValue(":path").setString("/simple");
        headers.addValue(":authority").setString("localhost:" + getPort());
        for (Entry<String,String> customHeader : customHeaders.entrySet()) {
            headers.addValue(customHeader.getKey()).setString(customHeader.getValue());
        }
        State state = hpackEncoder.encode(headers, headersPayload);
        if (state != State.COMPLETE) {
            throw new Exception("Unable to build headers");
        }
        headersPayload.flip();

        log.debug("Headers payload generated of size [" + headersPayload.limit() + "]");
    }


    private void populateFrameHeader(byte[] frameHeader, int written, int left, int thisTime,
            int streamId) throws Exception {
        ByteUtil.setThreeBytes(frameHeader, 0, thisTime);
        if (written == 0) {
            frameHeader[3] = FrameType.HEADERS.getIdByte();
            // Flags. End of stream
            frameHeader[4] = 0x01;
        } else {
            frameHeader[3] = FrameType.CONTINUATION.getIdByte();
        }
        if (left == thisTime) {
            // Flags. End of headers
            frameHeader[4] = (byte) (frameHeader[4] + 0x04);
        }

        // Stream id
        ByteUtil.set31Bits(frameHeader, 5, streamId);
    }
}