/*
 * Copyright (c) 2013 TMate Software Ltd
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
package org.tmatesoft.hg.core;

import java.util.LinkedHashSet;

import org.tmatesoft.hg.internal.DirstateBuilder;
import org.tmatesoft.hg.internal.DirstateReader;
import org.tmatesoft.hg.internal.Experimental;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.repo.HgManifest.Flags;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.Path;

/**
 * WORK IN PROGRESS
 * 
 * Schedule files for addition and removal 
 * XXX and, perhaps, forget() functionality shall be here as well?
 * 
 * @since 1.1
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@Experimental(reason="Work in progress")
public class HgAddRemoveCommand extends HgAbstractCommand<HgAddRemoveCommand> {
	
	private final HgRepository repo;
	private final LinkedHashSet<Path> toAdd, toRemove;

	public HgAddRemoveCommand(HgRepository hgRepo) {
		repo = hgRepo;
		toAdd = new LinkedHashSet<Path>();
		toRemove = new LinkedHashSet<Path>();
	}

	/**
	 * Schedule specified files to get listed in dirstate as added
	 * 
	 * @param paths files to mark as added, additive
	 * @return <code>this</code> for convenience
	 */
	public HgAddRemoveCommand add(Path... paths) {
		if (paths == null) {
			throw new IllegalArgumentException();
		}
		for (Path p : paths) {
			toRemove.remove(p);
			toAdd.add(p);
		}
		return this;
	}
	
	/**
	 * Schedule specified files to be marked as removed
	 * 
	 * @param paths files to mark as removed, additive
	 * @return <code>this</code> for convenience
	 */
	public HgAddRemoveCommand remove(Path... paths) {
		if (paths == null) {
			throw new IllegalArgumentException();
		}
		for (Path p : paths) {
			toAdd.remove(p);
			toRemove.add(p);
		}
		return this;
	}
	
	public HgAddRemoveCommand addAll() {
		throw Internals.notImplemented();
	}
	
	public HgAddRemoveCommand forget(Path path) {
		throw Internals.notImplemented();
	}

	/**
	 * Perform scheduled addition/removal
	 * 
	 * @throws HgException
	 */
	public void execute() throws HgException {
		try {
			Internals implRepo = Internals.getInstance(repo);
			final DirstateBuilder dirstateBuilder = new DirstateBuilder(implRepo);
			dirstateBuilder.fillFrom(new DirstateReader(implRepo, new Path.SimpleSource()));
			for (Path p : toAdd) {
				dirstateBuilder.recordAdded(p, Flags.RegularFile, -1);
			}
			for (Path p : toRemove) {
				dirstateBuilder.recordRemoved(p);
			}
			dirstateBuilder.serialize();
		} catch (HgRuntimeException ex) {
			throw new HgLibraryFailureException(ex);
		}
	}
}
