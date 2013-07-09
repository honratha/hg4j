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

import java.util.List;

import org.tmatesoft.hg.internal.AddRevInspector;
import org.tmatesoft.hg.internal.COWTransaction;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.internal.PhasesHelper;
import org.tmatesoft.hg.internal.RepositoryComparator;
import org.tmatesoft.hg.internal.RevisionSet;
import org.tmatesoft.hg.internal.Transaction;
import org.tmatesoft.hg.repo.HgBundle;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgParentChildMap;
import org.tmatesoft.hg.repo.HgRemoteRepository;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.ProgressSupport;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgPullCommand extends HgAbstractCommand<HgPullCommand> {

	private final HgRepository repo;
	private HgRemoteRepository remote;

	public HgPullCommand(HgRepository hgRepo) {
		repo = hgRepo;
	}

	public HgPullCommand source(HgRemoteRepository hgRemote) {
		remote = hgRemote;
		return this;
	}

	public void execute() throws HgRemoteConnectionException, HgIOException, HgLibraryFailureException, CancelledException {
		final ProgressSupport progress = getProgressSupport(null);
		try {
			progress.start(100);
			// TODO refactor same code in HgIncomingCommand #getComparator and #getParentHelper
			final HgChangelog clog = repo.getChangelog();
			final HgParentChildMap<HgChangelog> parentHelper = new HgParentChildMap<HgChangelog>(clog);
			parentHelper.init();
			final RepositoryComparator comparator = new RepositoryComparator(parentHelper, remote);
			// get incoming revisions
			comparator.compare(new ProgressSupport.Sub(progress, 50), getCancelSupport(null, true));
			final List<Nodeid> common = comparator.getCommon();
			// get bundle with changes from remote
			HgBundle incoming = remote.getChanges(common);
			//
			// add revisions to changelog, manifest, files
			final Internals implRepo = HgInternals.getImplementationRepo(repo);
			final AddRevInspector insp;
			Transaction.Factory trFactory = new COWTransaction.Factory();
			Transaction tr = trFactory.create(repo);
			try {
				incoming.inspectAll(insp = new AddRevInspector(implRepo, tr));
				tr.commit();
			} catch (HgRuntimeException ex) {
				tr.rollback();
				throw ex;
			} catch (RuntimeException ex) {
				tr.rollback();
				throw ex;
			}
			progress.worked(45);
			RevisionSet added = insp.addedChangesets();
			
			// get remote phases, update local phases to match that of remote
			final PhasesHelper phaseHelper = new PhasesHelper(implRepo, parentHelper);
			if (phaseHelper.isCapableOfPhases()) {
				RevisionSet rsCommon = new RevisionSet(common);
				HgRemoteRepository.Phases remotePhases = remote.getPhases();
				if (remotePhases.isPublishingServer()) {
					final RevisionSet knownPublic = rsCommon.union(added);
					RevisionSet newDraft = phaseHelper.allDraft().subtract(knownPublic);
					RevisionSet newSecret = phaseHelper.allSecret().subtract(knownPublic);
					phaseHelper.updateRoots(newDraft.asList(), newSecret.asList());
				} else {
					// FIXME refactor reuse from HgPushCommand
				}
			}
			progress.worked(5);
		} catch (HgRuntimeException ex) {
			throw new HgLibraryFailureException(ex);
		} finally {
			progress.done();
		}
	}
}
