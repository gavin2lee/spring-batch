/*
 * Copyright 2006-2007 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.batch.item.file;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.FlushFailedException;
import org.springframework.batch.item.ItemStream;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.WriterNotOpenException;
import org.springframework.batch.item.file.mapping.FieldSet;
import org.springframework.batch.item.file.transform.LineAggregator;
import org.springframework.batch.item.util.ExecutionContextUserSupport;
import org.springframework.batch.item.util.FileUtils;
import org.springframework.batch.support.transaction.TransactionAwareBufferedWriter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

/**
 * This class is an item writer that writes data to a file or stream. The writer
 * also provides restart. The location of the output file is defined by a
 * {@link Resource} and must represent a writable file.<br/>
 * 
 * Uses buffered writer to improve performance.<br/>
 * 
 * The implementation is *not* thread-safe.
 * 
 * @author Waseem Malik
 * @author Tomas Slanina
 * @author Robert Kasanicky
 * @author Dave Syer
 */
public class FlatFileItemWriter<T> extends ExecutionContextUserSupport implements ItemWriter<T>, ItemStream,
		InitializingBean {

	private static final String DEFAULT_LINE_SEPARATOR = System.getProperty("line.separator");

	private static final String WRITTEN_STATISTICS_NAME = "written";

	private static final String RESTART_DATA_NAME = "current.count";

	private Resource resource;

	private OutputState state = null;

	private LineAggregator<T> lineAggregator;

	private boolean saveState = true;

	private boolean shouldDeleteIfExists = true;

	private String encoding = OutputState.DEFAULT_CHARSET;

	private List<String> headerLines = new ArrayList<String>();

	private String lineSeparator = DEFAULT_LINE_SEPARATOR;

	public FlatFileItemWriter() {
		setName(ClassUtils.getShortName(FlatFileItemWriter.class));
	}

	/**
	 * Assert that mandatory properties (lineAggregator) are set.
	 * 
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet()
	 */
	public void afterPropertiesSet() throws Exception {
		Assert.notNull(lineAggregator, "A LineAggregator must be provided.");
	}

	/**
	 * Public setter for the line separator. Defaults to the System property
	 * line.separator.
	 * @param lineSeparator the line separator to set
	 */
	public void setLineSeparator(String lineSeparator) {
		this.lineSeparator = lineSeparator;
	}

	/**
	 * Public setter for the {@link LineAggregator}. This will be used to
	 * translate a {@link FieldSet} into a line for output.
	 * 
	 * @param lineAggregator the {@link LineAggregator} to set
	 */
	public void setLineAggregator(LineAggregator<T> lineAggregator) {
		this.lineAggregator = lineAggregator;
	}

	/**
	 * Setter for resource. Represents a file that can be written.
	 * 
	 * @param resource
	 */
	public void setResource(Resource resource) {
		this.resource = resource;
	}

	/**
	 * Sets encoding for output template.
	 */
	public void setEncoding(String newEncoding) {
		this.encoding = newEncoding;
	}

	/**
	 * @param shouldDeleteIfExists the shouldDeleteIfExists to set
	 */
	public void setShouldDeleteIfExists(boolean shouldDeleteIfExists) {
		this.shouldDeleteIfExists = shouldDeleteIfExists;
	}

	/**
	 * Set the flag indicating whether or not state should be saved in the
	 * provided {@link ExecutionContext} during the {@link ItemStream} call to
	 * update. Setting this to false means that it will always start at the
	 * beginning on a restart.
	 * 
	 * @param saveState
	 */
	public void setSaveState(boolean saveState) {
		this.saveState = saveState;
	}

	/**
	 * Public setter for the header lines. These will be output at the head of
	 * the file before any calls to {@link #write(List)} (and not on restart
	 * unless the restart is after a failure before the first flush).
	 * 
	 * @param headerLines the header lines to set
	 */
	public void setHeaderLines(String[] headerLines) {
		this.headerLines = Arrays.asList(headerLines);
	}

	/**
	 * Writes out a string followed by a "new line", where the format of the new
	 * line separator is determined by the underlying operating system. If the
	 * input is not a String and a converter is available the converter will be
	 * applied and then this method recursively called with the result. If the
	 * input is an array or collection each value will be written to a separate
	 * line (recursively calling this method for each value). If no converter is
	 * supplied the input object's toString method will be used.<br/>
	 * 
	 * @param items list of items to be written to output stream
	 * @throws Exception if the transformer or file output fail,
	 * WriterNotOpenException if the writer has not been initialized.
	 */
	public void write(List<? extends T> items) throws Exception {

		if (!getOutputState().isInitialized()) {
			throw new WriterNotOpenException("Writer must be open before it can be written to");
		}

		OutputState state = getOutputState();

		for (T item : items) {
			String line = lineAggregator.aggregate(item) + lineSeparator;
			try {
				state.write(line);
			}
			catch (IOException e) {
				throw new FlushFailedException("Could not write data.  The file may be corrupt.", e);
			}
		}

	}

	/**
	 * @see ItemStream#close(ExecutionContext)
	 */
	public void close(ExecutionContext executionContext) {
		if (state != null) {
			getOutputState().close();
			state = null;
		}
	}

	/**
	 * Initialize the reader. This method may be called multiple times before
	 * close is called.
	 * 
	 * @see ItemStream#open(ExecutionContext)
	 */
	public void open(ExecutionContext executionContext) throws ItemStreamException {

		Assert.notNull(resource, "The resource must be set");

		if (!getOutputState().isInitialized()) {
			doOpen(executionContext);
		}
	}

	private void doOpen(ExecutionContext executionContext) throws ItemStreamException {
		OutputState outputState = getOutputState();
		if (executionContext.containsKey(getKey(RESTART_DATA_NAME))) {
			outputState.restoreFrom(executionContext);
		}
		try {
			outputState.initializeBufferedWriter();
		}
		catch (IOException ioe) {
			throw new ItemStreamException("Failed to initialize writer", ioe);
		}
		if (outputState.lastMarkedByteOffsetPosition == 0) {
			try {
				for (String line : headerLines) {
					outputState.write(line + lineSeparator);
				}
			}
			catch (IOException e) {
				throw new FlushFailedException("Could not write headers.  The file may be corrupt.", e);
			}
		}
	}

	/**
	 * @see ItemStream#update(ExecutionContext)
	 */
	public void update(ExecutionContext executionContext) {
		if (state == null) {
			throw new ItemStreamException("ItemStream not open or already closed.");
		}

		Assert.notNull(executionContext, "ExecutionContext must not be null");

		if (saveState) {

			try {
				executionContext.putLong(getKey(RESTART_DATA_NAME), state.position());
			}
			catch (IOException e) {
				throw new ItemStreamException("ItemStream does not return current position properly", e);
			}

			executionContext.putLong(getKey(WRITTEN_STATISTICS_NAME), state.linesWritten);
		}
	}

	// Returns object representing state.
	private OutputState getOutputState() {
		if (state == null) {
			try {
				File file = resource.getFile();
				Assert.state(!file.exists() || file.canWrite(), "Resource is not writable: [" + resource + "]");
			}
			catch (IOException e) {
				throw new ItemStreamException("Could not test resource for writable status.", e);
			}
			state = new OutputState();
			state.setDeleteIfExists(shouldDeleteIfExists);
			state.setEncoding(encoding);
		}
		return (OutputState) state;
	}

	/**
	 * Encapsulates the runtime state of the writer. All state changing
	 * operations on the writer go through this class.
	 */
	private class OutputState {
		// default encoding for writing to output files - set to UTF-8.
		private static final String DEFAULT_CHARSET = "UTF-8";

		// The bufferedWriter over the file channel that is actually written
		Writer outputBufferedWriter;

		FileChannel fileChannel;

		// this represents the charset encoding (if any is needed) for the
		// output file
		String encoding = DEFAULT_CHARSET;

		boolean restarted = false;

		long lastMarkedByteOffsetPosition = 0;

		long linesWritten = 0;

		boolean shouldDeleteIfExists = true;

		boolean initialized = false;

		/**
		 * Return the byte offset position of the cursor in the output file as a
		 * long integer.
		 */
		public long position() throws IOException {
			long pos = 0;

			if (fileChannel == null) {
				return 0;
			}

			outputBufferedWriter.flush();
			pos = fileChannel.position();

			return pos;

		}

		/**
		 * @param executionContext
		 */
		public void restoreFrom(ExecutionContext executionContext) {
			lastMarkedByteOffsetPosition = executionContext.getLong(getKey(RESTART_DATA_NAME));
			restarted = true;
		}

		/**
		 * @param shouldDeleteIfExists
		 */
		public void setDeleteIfExists(boolean shouldDeleteIfExists) {
			this.shouldDeleteIfExists = shouldDeleteIfExists;
		}

		/**
		 * @param encoding
		 */
		public void setEncoding(String encoding) {
			this.encoding = encoding;
		}

		/**
		 * Close the open resource and reset counters.
		 */
		public void close() {
			initialized = false;
			restarted = false;
			try {
				if (outputBufferedWriter == null) {
					return;
				}
				outputBufferedWriter.close();
				fileChannel.close();
			}
			catch (IOException ioe) {
				throw new ItemStreamException("Unable to close the the ItemWriter", ioe);
			}
		}

		/**
		 * @param line
		 * @throws IOException
		 */
		public void write(String line) throws IOException {
			if (!initialized) {
				initializeBufferedWriter();
			}

			outputBufferedWriter.write(line);
			outputBufferedWriter.flush();
			linesWritten++;
		}

		/**
		 * Truncate the output at the last known good point.
		 * 
		 * @throws IOException
		 */
		public void truncate() throws IOException {
			fileChannel.truncate(lastMarkedByteOffsetPosition);
			fileChannel.position(lastMarkedByteOffsetPosition);
		}

		/**
		 * Creates the buffered writer for the output file channel based on
		 * configuration information.
		 * @throws IOException
		 */
		private void initializeBufferedWriter() throws IOException {

			File file = resource.getFile();

			FileUtils.setUpOutputFile(file, restarted, shouldDeleteIfExists);

			fileChannel = (new FileOutputStream(file.getAbsolutePath(), true)).getChannel();

			outputBufferedWriter = getBufferedWriter(fileChannel, encoding);

			// in case of restarting reset position to last committed point
			if (restarted) {
				checkFileSize();
				truncate();
			}

			initialized = true;
			linesWritten = 0;
		}

		public boolean isInitialized() {
			return initialized;
		}

		/**
		 * Returns the buffered writer opened to the beginning of the file
		 * specified by the absolute path name contained in absoluteFileName.
		 */
		private Writer getBufferedWriter(FileChannel fileChannel, String encoding) {
			try {
				TransactionAwareBufferedWriter outputBufferedWriter = new TransactionAwareBufferedWriter(Channels
						.newWriter(fileChannel, encoding));
				return outputBufferedWriter;
			}
			catch (UnsupportedCharsetException ucse) {
				throw new ItemStreamException("Bad encoding configuration for output file " + fileChannel, ucse);
			}
		}

		/**
		 * Checks (on setState) to make sure that the current output file's size
		 * is not smaller than the last saved commit point. If it is, then the
		 * file has been damaged in some way and whole task must be started over
		 * again from the beginning.
		 * @throws IOException if there is an IO problem
		 */
		private void checkFileSize() throws IOException {
			long size = -1;

			outputBufferedWriter.flush();
			size = fileChannel.size();

			if (size < lastMarkedByteOffsetPosition) {
				throw new ItemStreamException("Current file size is smaller than size at last commit");
			}
		}

	}

}
