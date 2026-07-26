/*
 * Copyright (c) 2026, s59
 * All rights reserved.
 */
package com.tcglocked;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class TcgSharedCardImageCacheTest
{
	@Test
	public void decodesValidImageWithinLimits() throws IOException
	{
		BufferedImage source = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ImageIO.write(source, "png", output);

		BufferedImage decoded = TcgSharedCardImageCache.decodeImage(
			new ByteArrayInputStream(output.toByteArray()));

		assertEquals(3, decoded.getWidth());
		assertEquals(2, decoded.getHeight());
	}

	@Test
	public void rejectsNonImageResponse() throws IOException
	{
		assertNull(TcgSharedCardImageCache.decodeImage(
			new ByteArrayInputStream("not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
	}
}
