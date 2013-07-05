/*
 * Copyright (c) 2010-2013 TMate Software Ltd
 *  
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * For information on how to redistribute this software under
 * the terms of a license other than GNU General Public License
 * contact TMate Software at support@hg4j.com
 */
package org.tmatesoft.hg.repo;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Formatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.Callback;
import org.tmatesoft.hg.internal.DataAccess;
import org.tmatesoft.hg.internal.Lifecycle;
import org.tmatesoft.hg.internal.LifecycleBridge;
import org.tmatesoft.hg.internal.Pool;
import org.tmatesoft.hg.internal.RevlogStream;
import org.tmatesoft.hg.util.Adaptable;
import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.ProgressSupport;

/**
 * Representation of the Mercurial changelog file (list of ChangeSets)
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class HgChangelog extends Revlog {

	/* package-local */HgChangelog(HgRepository hgRepo, RevlogStream content) {
		super(hgRepo, content, true);
	}

	/**
	 * Iterate over whole changelog
	 * @param inspector callback to process entries
	 * @throws HgInvalidControlFileException if failed to access revlog index/data entry. <em>Runtime exception</em>
	 * @throws HgRuntimeException subclass thereof to indicate other issues with the library. <em>Runtime exception</em>
	 */
	public void all(final HgChangelog.Inspector inspector) throws HgRuntimeException {
		range(0, getLastRevision(), inspector);
	}

	/**
	 * Iterate over changelog part
	 * @param start first changelog entry to process
	 * @param end last changelog entry to process
	 * @param inspector callback to process entries
	 * @throws HgInvalidRevisionException if any supplied revision doesn't identify revision from this revlog. <em>Runtime exception</em>
	 * @throws HgInvalidControlFileException if failed to access revlog index/data entry. <em>Runtime exception</em>
	 * @throws HgRuntimeException subclass thereof to indicate other issues with the library. <em>Runtime exception</em>
	 */
	public void range(int start, int end, final HgChangelog.Inspector inspector) throws HgRuntimeException {
		if (inspector == null) {
			throw new IllegalArgumentException();
		}
		content.iterate(start, end, true, new RawCsetParser(inspector));
	}

	/**
	 * @see #range(int, int, Inspector)
	 * @return changeset entry objects, never <code>null</code>
	 * @throws HgInvalidRevisionException if any supplied revision doesn't identify revision from this revlog. <em>Runtime exception</em>
	 * @throws HgInvalidControlFileException if failed to access revlog index/data entry. <em>Runtime exception</em>
	 * @throws HgRuntimeException subclass thereof to indicate other issues with the library. <em>Runtime exception</em>
	 */
	public List<RawChangeset> range(int start, int end) throws HgRuntimeException {
		final RawCsetCollector c = new RawCsetCollector(end - start + 1);
		range(start, end, c);
		return c.result;
	}

	/**
	 * Access individual revisions. Note, regardless of supplied revision order, inspector gets
	 * changesets strictly in the order they are in the changelog.
	 * @param inspector callback to get changesets
	 * @param revisions revisions to read, unrestricted ordering.
	 * @throws HgInvalidRevisionException if any supplied revision doesn't identify revision from this revlog <em>Runtime exception</em>
	 * @throws HgInvalidControlFileException if failed to access revlog index/data entry. <em>Runtime exception</em>
	 * @throws HgRuntimeException subclass thereof to indicate other issues with the library. <em>Runtime exception</em>
	 */
	public void range(final HgChangelog.Inspector inspector, final int... revisions) throws HgRuntimeException {
		Arrays.sort(revisions);
		rangeInternal(inspector, revisions);
	}

	/**
	 * Friends-only version of {@link #range(Inspector, int...)}, when callers know array is sorted
	 */
	/*package-local*/ void rangeInternal(HgChangelog.Inspector inspector, int[] sortedRevisions) throws HgRuntimeException {
		if (sortedRevisions == null || sortedRevisions.length == 0) {
			return;
		}
		if (inspector == null) {
			throw new IllegalArgumentException();
		}
		content.iterate(sortedRevisions, true, new RawCsetParser(inspector));
	}

	/**
	 * Get changeset entry object
	 * @throws HgInvalidRevisionException if supplied nodeid doesn't identify any revision from this revlog. <em>Runtime exception</em>
	 * @throws HgInvalidControlFileException if failed to access revlog index/data entry. <em>Runtime exception</em>
	 * @throws HgRuntimeException subclass thereof to indicate other issues with the library. <em>Runtime exception</em>
	 */
	public RawChangeset changeset(Nodeid nid)  throws HgRuntimeException {
		int x = getRevisionIndex(nid);
		return range(x, x).get(0);
	}

	@Callback
	public interface Inspector {
		/**
		 * Access next changeset
		 * TODO describe what revisionNumber is when Inspector is used with HgBundle (BAD_REVISION or bundle's local order?)
		 * 
		 * @param revisionIndex index of revision being inspected, local to the inspected object 
		 * @param nodeid revision being inspected
		 * @param cset changeset raw data
		 */
		void next(int revisionIndex, Nodeid nodeid, RawChangeset cset) throws HgRuntimeException;
	}

	/**
	 * Entry in the Changelog
	 */
	public static class RawChangeset implements Cloneable /* for those that would like to keep a copy */{
		// TODO immutable
		private/* final */Nodeid manifest;
		private String user;
		private String comment;
		private List<String> files; // unmodifiable collection (otherwise #files() and implicit #clone() shall be revised)
		private Date time;
		private int timezone;
		// http://mercurial.selenic.com/wiki/PruningDeadBranches - Closing changesets can be identified by close=1 in the changeset's extra field.
		private Map<String, String> extras;

		/**
		 * @see mercurial/changelog.py:read()
		 * 
		 *      <pre>
		 *         format used:
		 *         nodeid\n        : manifest node in ascii
		 *         user\n          : user, no \n or \r allowed
		 *         time tz extra\n : date (time is int or float, timezone is int)
		 *                         : extra is metadatas, encoded and separated by '\0'
		 *                         : older versions ignore it
		 *         files\n\n       : files modified by the cset, no \n or \r allowed
		 *         (.*)            : comment (free text, ideally utf-8)
		 * 
		 *         changelog v0 doesn't use extra
		 * </pre>
		 */
		private RawChangeset() {
		}

		public Nodeid manifest() {
			return manifest;
		}

		public String user() {
			return user;
		}

		public String comment() {
			return comment;
		}

		public List<String> files() {
			return files;
		}

		public Date date() {
			return time;
		}
		
		/**
		 * @return time zone value, as is, positive for Western Hemisphere.
		 */
		public int timezone() {
			return timezone;
		}

		public String dateString() {
			// XXX keep once formatted? Perhaps, there's faster way to set up calendar/time zone?
			StringBuilder sb = new StringBuilder(30);
			Formatter f = new Formatter(sb, Locale.US);
			TimeZone tz = TimeZone.getTimeZone(TimeZone.getAvailableIDs(timezone * 1000)[0]);
			// apparently timezone field records number of seconds time differs from UTC,
			// i.e. value to substract from time to get UTC time. Calendar seems to add
			// timezone offset to UTC, instead, hence sign change.
//			tz.setRawOffset(timezone * -1000);
			Calendar c = Calendar.getInstance(tz, Locale.US);
			c.setTime(time);
			f.format("%ta %<tb %<td %<tH:%<tM:%<tS %<tY %<tz", c);
			return sb.toString();
		}

		public Map<String, String> extras() {
			return extras;
		}

		public String branch() {
			return extras.get("branch");
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();
			sb.append("Changeset {");
			sb.append("User: ").append(user).append(", ");
			sb.append("Comment: ").append(comment).append(", ");
			sb.append("Manifest: ").append(manifest).append(", ");
			sb.append("Date: ").append(time).append(", ");
			sb.append("Files: ").append(files.size());
			for (String s : files) {
				sb.append(", ").append(s);
			}
			if (extras != null) {
				sb.append(", Extra: ").append(extras);
			}
			sb.append("}");
			return sb.toString();
		}

		@Override
		public RawChangeset clone() {
			try {
				return (RawChangeset) super.clone();
			} catch (CloneNotSupportedException ex) {
				throw new InternalError(ex.toString());
			}
		}

		/*package*/ static RawChangeset parse(DataAccess da) throws IOException, HgInvalidDataFormatException {
			byte[] data = da.byteArray();
			RawChangeset rv = new RawChangeset();
			rv.init(data, 0, data.length, null);
			return rv;
		}

		// @param usersPool - it's likely user names get repeated again and again throughout repository. can be null
		/* package-local */void init(byte[] data, int offset, int length, Pool<String> usersPool) throws HgInvalidDataFormatException {
			final int bufferEndIndex = offset + length;
			final byte lineBreak = (byte) '\n';
			int breakIndex1 = indexOf(data, lineBreak, offset, bufferEndIndex);
			if (breakIndex1 == -1) {
				throw new HgInvalidDataFormatException("Bad Changeset data");
			}
			Nodeid _nodeid = Nodeid.fromAscii(data, 0, breakIndex1);
			int breakIndex2 = indexOf(data, lineBreak, breakIndex1 + 1, bufferEndIndex);
			if (breakIndex2 == -1) {
				throw new HgInvalidDataFormatException("Bad Changeset data");
			}
			String _user;
			try {
				// TODO use encoding helper? Although where encoding is fixed (like here), seems to be just too much
				_user = new String(data, breakIndex1 + 1, breakIndex2 - breakIndex1 - 1, "UTF-8");
				if (usersPool != null) {
					_user = usersPool.unify(_user);
				}
			} catch (UnsupportedEncodingException ex) {
				_user = "";
				// Could hardly happen
				throw new HgInvalidDataFormatException("Bad Changeset data", ex);
			}

			int breakIndex3 = indexOf(data, lineBreak, breakIndex2 + 1, bufferEndIndex);
			if (breakIndex3 == -1) {
				throw new HgInvalidDataFormatException("Bad Changeset data");
			}
			String _timeString = new String(data, breakIndex2 + 1, breakIndex3 - breakIndex2 - 1);
			int space1 = _timeString.indexOf(' ');
			if (space1 == -1) {
				throw new HgInvalidDataFormatException(String.format("Bad Changeset data: %s in [%d..%d]", "time string", breakIndex2+1, breakIndex3));
			}
			int space2 = _timeString.indexOf(' ', space1 + 1);
			if (space2 == -1) {
				space2 = _timeString.length();
			}
			long unixTime = Long.parseLong(_timeString.substring(0, space1));
			int _timezone = Integer.parseInt(_timeString.substring(space1 + 1, space2));
			// unixTime is local time, and timezone records difference of the local time to UTC.
			Date _time = new Date(unixTime * 1000);
			String _extras = space2 < _timeString.length() ? _timeString.substring(space2 + 1) : null;
			Map<String, String> _extrasMap;
			final String extras_branch_key = "branch";
			if (_extras == null || _extras.trim().length() == 0) {
				_extrasMap = Collections.singletonMap(extras_branch_key, HgRepository.DEFAULT_BRANCH_NAME);
			} else {
				_extrasMap = new HashMap<String, String>();
				for (String pair : _extras.split("\00")) {
					pair = decode(pair);
					int eq = pair.indexOf(':');
					_extrasMap.put(pair.substring(0, eq), pair.substring(eq + 1));
				}
				if (!_extrasMap.containsKey(extras_branch_key)) {
					_extrasMap.put(extras_branch_key, HgRepository.DEFAULT_BRANCH_NAME);
				}
				_extrasMap = Collections.unmodifiableMap(_extrasMap);
			}

			//
			int lastStart = breakIndex3 + 1;
			int breakIndex4 = indexOf(data, lineBreak, lastStart, bufferEndIndex);
			ArrayList<String> _files = null;
			if (breakIndex4 > lastStart) {
				// if breakIndex4 == lastStart, we already found \n\n and hence there are no files (e.g. merge revision)
				_files = new ArrayList<String>(5);
				while (breakIndex4 != -1 && breakIndex4 + 1 < bufferEndIndex) {
					_files.add(new String(data, lastStart, breakIndex4 - lastStart));
					lastStart = breakIndex4 + 1;
					if (data[breakIndex4 + 1] == lineBreak) {
						// found \n\n
						break;
					} else {
						breakIndex4 = indexOf(data, lineBreak, lastStart, bufferEndIndex);
					}
				}
				if (breakIndex4 == -1 || breakIndex4 >= bufferEndIndex) {
					throw new HgInvalidDataFormatException("Bad Changeset data");
				}
			} else {
				breakIndex4--;
			}
			String _comment;
			try {
				_comment = new String(data, breakIndex4 + 2, bufferEndIndex - breakIndex4 - 2, "UTF-8");
				// TODO post-1.0 respect ui.fallbackencoding and try to decode if set; use EncodingHelper
			} catch (UnsupportedEncodingException ex) {
				_comment = "";
				// Could hardly happen
				throw new HgInvalidDataFormatException("Bad Changeset data", ex);
			}
			// change this instance at once, don't leave it partially changes in case of error
			this.manifest = _nodeid;
			this.user = _user;
			this.time = _time;
			this.timezone = _timezone;
			this.files = _files == null ? Collections.<String> emptyList() : Collections.unmodifiableList(_files);
			this.comment = _comment;
			this.extras = _extrasMap;
		}

		private static int indexOf(byte[] src, byte what, int startOffset, int endIndex) {
			for (int i = startOffset; i < endIndex; i++) {
				if (src[i] == what) {
					return i;
				}
			}
			return -1;
		}
		
		private static String decode(String s) {
			if (s != null && s.indexOf('\\') != -1) {
				// TestAuxUtilities#testChangelogExtrasDecode
				return s.replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r").replace("\\0", "\00");
			}
			return s;
		}
	}

	private static class RawCsetCollector implements Inspector {
		final ArrayList<RawChangeset> result;
		
		public RawCsetCollector(int count) {
			result = new ArrayList<RawChangeset>(count > 0 ? count : 5);
		}

		public void next(int revisionNumber, Nodeid nodeid, RawChangeset cset) {
			result.add(cset.clone());
		}
	}

	private static class RawCsetParser implements RevlogStream.Inspector, Adaptable {
		
		private final Inspector inspector;
		private final Pool<String> usersPool;
		private final RawChangeset cset = new RawChangeset();
		// non-null when inspector uses high-level lifecycle entities (progress and/or cancel supports)
		private final LifecycleBridge lifecycleStub;
		// non-null when inspector relies on low-level lifecycle and is responsible
		// to proceed any possible high-level entities himself.
		private final Lifecycle inspectorLifecycle;

		public RawCsetParser(HgChangelog.Inspector delegate) {
			assert delegate != null;
			inspector = delegate;
			usersPool = new Pool<String>();
			inspectorLifecycle = Adaptable.Factory.getAdapter(delegate, Lifecycle.class, null);
			if (inspectorLifecycle == null) {
				ProgressSupport ph = Adaptable.Factory.getAdapter(delegate, ProgressSupport.class, null);
				CancelSupport cs = Adaptable.Factory.getAdapter(delegate, CancelSupport.class, null);
				if (cs != null || ph != null) {
					lifecycleStub = new LifecycleBridge(ph, cs);
				} else {
					lifecycleStub = null;
				}
			} else {
				lifecycleStub = null;
			}
		}

		public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess da) throws HgRuntimeException {
			try {
				byte[] data = da.byteArray();
				cset.init(data, 0, data.length, usersPool);
				// XXX there's no guarantee for Changeset.Callback that distinct instance comes each time, consider instance reuse
				inspector.next(revisionNumber, Nodeid.fromBinary(nodeid, 0), cset);
				if (lifecycleStub != null) {
					lifecycleStub.nextStep();
				}
			} catch (HgInvalidDataFormatException ex) {
				throw ex.setRevisionIndex(revisionNumber);  
			} catch (IOException ex) {
				// XXX need better exception, perhaps smth like HgChangelogException (extends HgInvalidControlFileException)
				throw new HgInvalidControlFileException("Failed reading changelog", ex, null).setRevisionIndex(revisionNumber);  
			}
		}
		
		public <T> T getAdapter(Class<T> adapterClass) {
			if (adapterClass == Lifecycle.class) {
				if (inspectorLifecycle != null) {
					return adapterClass.cast(inspectorLifecycle);
				}
				// reveal interest in lifecycle only when either progress or cancel support is there
				// and inspector itself doesn't respond to lifecycle request
				// lifecycleStub may still be null here (no progress and cancel), it's ok to cast(null) 
				return adapterClass.cast(lifecycleStub);
				
			}
			return Adaptable.Factory.getAdapter(inspector, adapterClass, null);
		}

	}
}
