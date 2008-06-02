/*
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org>
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com>
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * - Neither the name of the Git Development Community nor the
 *   names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.spearce.jgit.lib;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Iterator;

import org.spearce.jgit.util.NB;

/**
 * Access path to locate objects by {@link ObjectId} in a {@link PackFile}.
 * <p>
 * Indexes are strictly redundant information in that we can rebuild all of the
 * data held in the index file from the on disk representation of the pack file
 * itself, but it is faster to access for random requests because data is stored
 * by ObjectId.
 * </p>
 */
public abstract class PackIndex implements Iterable<PackIndex.MutableEntry> {
	/**
	 * Open an existing pack <code>.idx</code> file for reading.
	 * <p>
	 * The format of the file will be automatically detected and a proper access
	 * implementation for that format will be constructed and returned to the
	 * caller. The file may or may not be held open by the returned instance.
	 * </p>
	 * 
	 * @param idxFile
	 *            existing pack .idx to read.
	 * @return access implementation for the requested file.
	 * @throws FileNotFoundException
	 *             the file does not exist.
	 * @throws IOException
	 *             the file exists but could not be read due to security errors,
	 *             unrecognized data version, or unexpected data corruption.
	 */
	public static PackIndex open(final File idxFile) throws IOException {
		final FileInputStream fd = new FileInputStream(idxFile);
		try {
			final byte[] hdr = new byte[8];
			NB.readFully(fd, hdr, 0, hdr.length);
			if (isTOC(hdr)) {
				final int v = NB.decodeInt32(hdr, 4);
				switch (v) {
				case 2:
					return new PackIndexV2(fd);
				default:
					throw new IOException("Unsupported pack index version " + v);
				}
			}
			return new PackIndexV1(fd, hdr);
		} catch (IOException ioe) {
			final String path = idxFile.getAbsolutePath();
			final IOException err;
			err = new IOException("Unreadable pack index: " + path);
			err.initCause(ioe);
			throw err;
		} finally {
			try {
				fd.close();
			} catch (IOException err2) {
				// ignore
			}
		}
	}

	private static boolean isTOC(final byte[] h) {
		return h[0] == -1 && h[1] == 't' && h[2] == 'O' && h[3] == 'c';
	}

	/**
	 * Determine if an object is contained within the pack file.
	 * 
	 * @param id
	 *            the object to look for. Must not be null.
	 * @return true if the object is listed in this index; false otherwise.
	 */
	public boolean hasObject(final AnyObjectId id) {
		return findOffset(id) != -1;
	}

	/**
	 * Provide iterator that gives access to index entries. Note, that iterator
	 * returns reference to mutable object, the same reference in each call -
	 * for performance reason. If client needs immutable objects, it must copy
	 * returned object on its own.
	 * <p>
	 * Iterator returns objects in SHA-1 lexicographical order.
	 * </p>
	 * 
	 * @return iterator over pack index entries
	 */
	public abstract Iterator<MutableEntry> iterator();

	/**
	 * Obtain the total number of objects described by this index.
	 * 
	 * @return number of objects in this index, and likewise in the associated
	 *         pack that this index was generated from.
	 */
	abstract long getObjectCount();

	/**
	 * Locate the file offset position for the requested object.
	 * 
	 * @param objId
	 *            name of the object to locate within the pack.
	 * @return offset of the object's header and compressed content; -1 if the
	 *         object does not exist in this index and is thus not stored in the
	 *         associated pack.
	 */
	abstract long findOffset(AnyObjectId objId);

	/**
	 * Represent mutable entry of pack index consisting of object id and offset
	 * in pack (both mutable).
	 * 
	 */
	public static class MutableEntry extends MutableObjectId {
		private long offset;

		/**
		 * Empty constructor. Object fields should be filled in later.
		 */
		public MutableEntry() {
			super();
		}

		/**
		 * Returns offset for this index object entry
		 * 
		 * @return offset of this object in a pack file
		 */
		public long getOffset() {
			return offset;
		}

		void setOffset(long offset) {
			this.offset = offset;
		}

		private MutableEntry(MutableEntry src) {
			super(src);
			this.offset = src.offset;
		}

		/**
		 * Returns mutable copy of this mutable entry.
		 * 
		 * @return copy of this mutable entry
		 */
		public MutableEntry cloneEntry() {
			return new MutableEntry(this);
		}
	}

	protected abstract class EntriesIterator implements Iterator<MutableEntry> {
		protected MutableEntry objectId = new MutableEntry();

		protected long returnedNumber = 0;

		public boolean hasNext() {
			return returnedNumber < getObjectCount();
		}

		/**
		 * Implementation must update {@link #returnedNumber} before returning
		 * element.
		 */
		public abstract MutableEntry next();

		public void remove() {
			throw new UnsupportedOperationException();
		}
	}
}
