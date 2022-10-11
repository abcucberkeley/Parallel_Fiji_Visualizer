package edu.abc.berkeley;

import java.nio.ByteBuffer;

public class setPositions extends Thread {
	private int w;
	private int batchSize;
	private float[][] im;
	ByteBuffer bb;
	private long zSize;
	private long[] dims;
	
	public setPositions(int w, int batchSize, float[][] im, ByteBuffer bb, long zSize, long[] dims) {
		this.w = w;
		this.batchSize = batchSize;
		this.im = im;
		this.bb = bb;
		this.zSize = zSize;
		this.dims = dims;
	}
	
	@Override
	public void run() {
		for(int j = 0; j < zSize; j++) {
        	im[0][j] = bb.asFloatBuffer().get(j);
        }
	}
}
