/*******************************************************************************
 * Copyright (c) 2009, 2021 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.equinox.p2.internal.repository.tools;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import org.eclipse.core.runtime.*;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.equinox.internal.p2.core.helpers.LogHelper;
import org.eclipse.equinox.internal.p2.director.PermissiveSlicer;
import org.eclipse.equinox.internal.p2.director.Slicer;
import org.eclipse.equinox.internal.p2.repository.Transport;
import org.eclipse.equinox.internal.p2.repository.helpers.RepositoryHelper;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.core.ProvisionException;
import org.eclipse.equinox.p2.engine.*;
import org.eclipse.equinox.p2.internal.repository.comparator.ArtifactChecksumComparator;
import org.eclipse.equinox.p2.internal.repository.mirroring.*;
import org.eclipse.equinox.p2.metadata.*;
import org.eclipse.equinox.p2.planner.IPlanner;
import org.eclipse.equinox.p2.planner.IProfileChangeRequest;
import org.eclipse.equinox.p2.query.*;
import org.eclipse.equinox.p2.repository.artifact.IArtifactDescriptor;
import org.eclipse.equinox.p2.repository.artifact.IArtifactRepository;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepository;
import org.eclipse.osgi.util.NLS;

public class MirrorApplication extends AbstractApplication implements IApplication, IExecutableExtension {
	private static final String DEFAULT_COMPARATOR = ArtifactChecksumComparator.COMPARATOR_ID + ".sha-256"; //$NON-NLS-1$
	private static final String LOG_ROOT = "p2.mirror"; //$NON-NLS-1$
	private static final String MIRROR_MODE = "metadataOrArtifacts"; //$NON-NLS-1$

	protected SlicingOptions slicingOptions = new SlicingOptions();

	private URI baseline;
	private String comparatorID;
	private IQuery<IArtifactDescriptor> compareExclusions = null;
	private boolean compare = false;
	private boolean failOnError = true;
	private boolean raw = true;
	private boolean verbose = false;
	private boolean validate = false;
	private boolean mirrorReferences = true;
	private String metadataOrArtifacts = null;
	private String[] rootIUs = null;
	private boolean mirrorProperties = false;

	private File mirrorLogFile; // file to log mirror output to (optional)
	private File comparatorLogFile; // file to comparator output to (optional)
	private IArtifactMirrorLog mirrorLog;
	private IArtifactMirrorLog comparatorLog;

	public MirrorApplication() {
		super();
	}

	public MirrorApplication(IProvisioningAgent agent) {
		super(agent);
	}

	/**
	 * Convert a list of tokens into an array. The list separator has to be
	 * specified.
	 */
	public static String[] getArrayArgsFromString(String list, String separator) {
		if (list == null || list.trim().equals("")) { //$NON-NLS-1$
			return new String[0];
		}
		List<String> result = new ArrayList<>();
		for (StringTokenizer tokens = new StringTokenizer(list, separator); tokens.hasMoreTokens();) {
			String token = tokens.nextToken().trim();
			if (!token.equals("")) { //$NON-NLS-1$
				if ((token.indexOf('[') >= 0 || token.indexOf('(') >= 0) && tokens.hasMoreTokens()) {
					result.add(token + separator + tokens.nextToken());
				} else {
					result.add(token);
				}
			}
		}
		return result.toArray(new String[result.size()]);
	}

	@Override
	public Object start(IApplicationContext context) throws Exception {
		Map<?, ?> args = context.getArguments();
		initializeFromArguments((String[]) args.get(IApplicationContext.APPLICATION_ARGS));
		run(null);
		return IApplication.EXIT_OK;
	}

	@Override
	public void stop() { // nothing to do
	}

	/*
	 * The old "org.eclipse.equinox.p2.artifact.repository.mirrorApplication"
	 * application only does artifacts Similary,
	 * "org.eclipse.equinox.p2.metadata.repository.mirrorApplication" only does
	 * metadata
	 */
	@Override
	public void setInitializationData(IConfigurationElement config, String propertyName, Object data) {
		if (data instanceof Map<?, ?> && ((Map<?, ?>) data).containsKey(MIRROR_MODE)) {
			metadataOrArtifacts = (String) ((Map<?, ?>) data).get(MIRROR_MODE);
		}
	}

	public void initializeFromArguments(String[] args) {
		if (args == null) {
			return;
		}

		File comparatorLogLocation = null;
		File mirrorLogLocation = null;

		RepositoryDescriptor destination = new RepositoryDescriptor();
		RepositoryDescriptor sourceRepo = new RepositoryDescriptor();
		if (metadataOrArtifacts != null) {
			destination.setKind(metadataOrArtifacts);
			sourceRepo.setKind(metadataOrArtifacts);
		}

		addDestination(destination);
		addSource(sourceRepo);

		for (int i = 0; i < args.length; i++) {
			// check for args without parameters (i.e., a flag arg)
			if (args[i].equalsIgnoreCase("-raw")) { //$NON-NLS-1$
				raw = true;
			} else if (args[i].equalsIgnoreCase("-ignoreErrors")) { //$NON-NLS-1$
				failOnError = false;
			} else if (args[i].equalsIgnoreCase("-verbose")) { //$NON-NLS-1$
				verbose = true;
			} else if (args[i].equalsIgnoreCase("-compare")) { //$NON-NLS-1$
				compare = true;
			} else if (args[i].equalsIgnoreCase("-validate")) { //$NON-NLS-1$
				validate = true;
			} else if (args[i].equalsIgnoreCase("-references")) { //$NON-NLS-1$
				mirrorReferences = true;
			} else if (args[i].equalsIgnoreCase("-properties")) { //$NON-NLS-1$
				mirrorProperties = true;
			}

			// check for args with parameters. If we are at the last argument or
			// if the next one has a '-' as the first character, then we can't have
			// an arg with a param so continue.
			if (i == args.length - 1 || args[i + 1].startsWith("-")) { //$NON-NLS-1$
				continue;
			}

			String arg = args[++i];

			if (args[i - 1].equalsIgnoreCase("-comparator")) { //$NON-NLS-1$
				comparatorID = arg;
			} else if (args[i - 1].equalsIgnoreCase("-comparatorLog")) { //$NON-NLS-1$
				comparatorLogLocation = new File(arg);
			} else if (args[i - 1].equalsIgnoreCase("-destinationName")) { //$NON-NLS-1$
				destination.setName(arg);
			} else if (args[i - 1].equalsIgnoreCase("-writeMode")) { //$NON-NLS-1$
				if (args[i].equalsIgnoreCase("clean")) { //$NON-NLS-1$
					destination.setAppend(false);
				}
			} else if (args[i - 1].equalsIgnoreCase("-log")) { //$NON-NLS-1$
				mirrorLogLocation = new File(arg);
			} else if (args[i - 1].equalsIgnoreCase("-roots")) { //$NON-NLS-1$
				rootIUs = getArrayArgsFromString(arg, ","); //$NON-NLS-1$
			} else if (args[i - 1].equalsIgnoreCase("-references")) {//$NON-NLS-1$
				mirrorReferences = Boolean.parseBoolean(args[i]);
			} else {
				try {
					if (args[i - 1].equalsIgnoreCase("-source")) { //$NON-NLS-1$
						URI uri = RepositoryHelper.localRepoURIHelper(URIUtil.fromString(arg));
						sourceRepo.setLocation(uri);
						destination.setFormat(uri);
					} else if (args[i - 1].equalsIgnoreCase("-destination")) { //$NON-NLS-1$
						destination.setLocation(RepositoryHelper.localRepoURIHelper(URIUtil.fromString(arg)));
					} else if (args[i - 1].equalsIgnoreCase("-compareAgainst")) { //$NON-NLS-1$
						baseline = RepositoryHelper.localRepoURIHelper(URIUtil.fromString(arg));
						compare = true;
					}
				} catch (URISyntaxException e) {
					throw new IllegalArgumentException(NLS.bind(Messages.ProcessRepo_location_not_url, arg));
				}
			}
		}

		// Create logs
		if (mirrorLogLocation != null) {
			mirrorLog = getLog(mirrorLogLocation, "p2.artifact.mirror"); //$NON-NLS-1$
		}
		if (comparatorLogLocation != null && comparatorID != null) {
			comparatorLog = getLog(comparatorLogLocation, comparatorID);
		}
	}

	@Override
	public IStatus run(IProgressMonitor monitor) throws ProvisionException {
		IStatus mirrorStatus = Status.OK_STATUS;
		try {
			initializeRepos(new NullProgressMonitor());
			initializeLogs();
			validate();
			initializeIUs();
			IQueryable<IInstallableUnit> slice = slice(new NullProgressMonitor());
			if (destinationArtifactRepository != null) {
				mirrorStatus = mirrorArtifacts(slice, new NullProgressMonitor());
				if (failOnError && mirrorStatus.getSeverity() == IStatus.ERROR) {
					return mirrorStatus;
				}
			}
			if (destinationMetadataRepository != null) {
				mirrorMetadata(slice, new NullProgressMonitor());
			}
		} finally {
			finalizeRepositories();
			finalizeLogs();
		}
		if (mirrorStatus.isOK()) {
			return Status.OK_STATUS;
		}
		return mirrorStatus;
	}

	private IStatus mirrorArtifacts(IQueryable<IInstallableUnit> slice, IProgressMonitor monitor) {
		Mirroring mirror = getMirroring(slice, monitor);

		IStatus result = mirror.run(failOnError, verbose);

		if (mirrorLog != null) {
			mirrorLog.log(result);
		} else {
			LogHelper.log(result);
		}
		return result;
	}

	protected Mirroring getMirroring(IQueryable<IInstallableUnit> slice, IProgressMonitor monitor) {
		// Obtain ArtifactKeys from IUs
		IQueryResult<IInstallableUnit> ius = slice.query(QueryUtil.createIUAnyQuery(), monitor);
		boolean iusSpecified = !ius.isEmpty(); // call before ius.iterator() to avoid bug 420318
		ArrayList<IArtifactKey> keys = new ArrayList<>();
		for (IInstallableUnit iu : ius) {
			keys.addAll(iu.getArtifacts());
		}

		Mirroring mirror = new Mirroring(getCompositeArtifactRepository(), destinationArtifactRepository, raw);
		mirror.setCompare(compare);
		mirror.setComparatorId(comparatorID == null ? DEFAULT_COMPARATOR : comparatorID);
		mirror.setBaseline(initializeBaseline());
		mirror.setValidate(validate);
		mirror.setCompareExclusions(compareExclusions);
		mirror.setTransport((Transport) agent.getService(Transport.SERVICE_NAME));
		mirror.setMirrorProperties(mirrorProperties);

		// If IUs have been specified then only they should be mirrored, otherwise
		// mirror everything.
		if (iusSpecified) {
			mirror.setArtifactKeys(keys.toArray(new IArtifactKey[keys.size()]));
		}

		if (comparatorLog != null) {
			mirror.setComparatorLog(comparatorLog);
		}
		return mirror;
	}

	private IArtifactRepository initializeBaseline() {
		if (baseline == null) {
			return null;
		}
		try {
			return (IArtifactRepository) addRepository(getArtifactRepositoryManager(), baseline, 0,
					artifactReposToRemove, null);
		} catch (ProvisionException e) {
			if (mirrorLog != null && e.getStatus() != null) {
				mirrorLog.log(e.getStatus());
			}
			return null;
		}
	}

	private void mirrorMetadata(IQueryable<IInstallableUnit> slice, IProgressMonitor monitor) {
		IQueryResult<IInstallableUnit> allIUs = slice.query(QueryUtil.createIUAnyQuery(), monitor);
		destinationMetadataRepository.addInstallableUnits(allIUs.toUnmodifiableSet());
		if (mirrorReferences) {
			destinationMetadataRepository.addReferences(getCompositeMetadataRepository().getReferences());
		}
	}

	/*
	 * Ensure all mandatory parameters have been set. Throw an exception if there
	 * are any missing. We don't require the user to specify the artifact repository
	 * here, we will default to the ones already registered in the manager. (callers
	 * are free to add more if they wish)
	 */
	private void validate() throws ProvisionException {
		if (sourceRepositories.isEmpty()) {
			throw new ProvisionException(Messages.MirrorApplication_set_source_repositories);
		}
		if (!hasArtifactSources() && destinationArtifactRepository != null) {
			throw new ProvisionException(Messages.MirrorApplication_artifactDestinationNoSource);
		}
		if (!hasMetadataSources() && destinationMetadataRepository != null) {
			throw new ProvisionException(Messages.MirrorApplication_metadataDestinationNoSource);
		}
	}

	/*
	 * If no IUs have been specified we want to mirror them all
	 */
	private void initializeIUs() throws ProvisionException {
		IMetadataRepository metadataRepo = getCompositeMetadataRepository();

		if (rootIUs != null) {
			sourceIUs = new ArrayList<>();
			for (String rootIU : rootIUs) {
				String[] segments = getArrayArgsFromString(rootIU, "/"); //$NON-NLS-1$
				VersionRange range = segments.length > 1 ? VersionRange.create(segments[1]) : null;
				Iterator<IInstallableUnit> queryResult = metadataRepo
						.query(QueryUtil.createIUQuery(segments[0], range), null).iterator();
				while (queryResult.hasNext()) {
					sourceIUs.add(queryResult.next());
				}
			}
		} else if (sourceIUs == null || sourceIUs.isEmpty()) {
			sourceIUs = new ArrayList<>();
			Iterator<IInstallableUnit> queryResult = metadataRepo.query(QueryUtil.createIUAnyQuery(), null).iterator();
			while (queryResult.hasNext()) {
				sourceIUs.add(queryResult.next());
			}
			/* old metadata mirroring app did not throw an exception here */
			if (sourceIUs.isEmpty() && destinationMetadataRepository != null && metadataOrArtifacts == null) {
				throw new ProvisionException(Messages.MirrorApplication_no_IUs);
			}
		}
	}

	/*
	 * Initialize logs, if applicable
	 */
	private void initializeLogs() {
		if (compare && comparatorLogFile != null) {
			comparatorLog = getLog(comparatorLogFile, comparatorID);
		}
		if (mirrorLog == null && mirrorLogFile != null) {
			mirrorLog = getLog(mirrorLogFile, LOG_ROOT);
		}
	}

	/*
	 * Finalize logs, if applicable
	 */
	private void finalizeLogs() {
		if (comparatorLog != null) {
			comparatorLog.close();
		}
		if (mirrorLog != null) {
			mirrorLog.close();
		}
	}

	/*
	 * Get the log for a location
	 */
	private IArtifactMirrorLog getLog(File location, String root) {
		String absolutePath = location.getAbsolutePath();
		if (absolutePath.toLowerCase().endsWith(".xml")) { //$NON-NLS-1$
			return new XMLMirrorLog(absolutePath, 0, root);
		}
		return new FileMirrorLog(absolutePath, 0, root);
	}

	private IQueryable<IInstallableUnit> performResolution(IProgressMonitor monitor) throws ProvisionException {
		IProfileRegistry registry = Activator.getProfileRegistry();
		String profileId = "MirrorApplication-" + System.currentTimeMillis(); //$NON-NLS-1$
		IProfile profile = registry.addProfile(profileId, slicingOptions.getFilter());
		IPlanner planner = Activator.getAgent().getService(IPlanner.class);
		if (planner == null) {
			throw new IllegalStateException();
		}
		IProfileChangeRequest pcr = planner.createChangeRequest(profile);
		pcr.addAll(sourceIUs);
		IProvisioningPlan plan = planner.getProvisioningPlan(pcr, null, monitor);
		registry.removeProfile(profileId);
		List<IQueryable<IInstallableUnit>> queriables = new ArrayList<>();
		queriables.add(plan.getAdditions());
		if (plan.getInstallerPlan() != null) {
			queriables.add(plan.getInstallerPlan().getAdditions());
		}
		return new CompoundQueryable<>(queriables);
	}

	private IQueryable<IInstallableUnit> slice(IProgressMonitor monitor) throws ProvisionException {
		if (slicingOptions == null) {
			slicingOptions = new SlicingOptions();
		}
		if (slicingOptions.getInstallTimeLikeResolution()) {
			return performResolution(monitor);
		}

		Slicer slicer = createSlicer(slicingOptions);
		IQueryable<IInstallableUnit> slice = slicer.slice(sourceIUs, monitor);

		if (slice != null && slicingOptions.latestVersionOnly()) {
			IQueryResult<IInstallableUnit> queryResult = slice.query(QueryUtil.createLatestIUQuery(), monitor);
			slice = queryResult;
		}
		if (slicer.getStatus().getSeverity() != IStatus.OK && mirrorLog != null) {
			mirrorLog.log(slicer.getStatus());
		}
		if (slice == null) {
			throw new ProvisionException(slicer.getStatus());
		}
		return slice;
	}

	protected Slicer createSlicer(SlicingOptions options) {
		return new PermissiveSlicer(getCompositeMetadataRepository(), options.getFilter(),
				options.includeOptionalDependencies(), options.isEverythingGreedy(), options.forceFilterTo(),
				options.considerStrictDependencyOnly(), options.followOnlyFilteredRequirements());
	}

	public void setSlicingOptions(SlicingOptions options) {
		slicingOptions = options;
	}

	/*
	 * Set the location of the baseline repository. (used in comparison)
	 */
	public void setBaseline(URI baseline) {
		this.baseline = baseline;
		compare = true;
	}

	/*
	 * Set the identifier of the comparator to use.
	 */
	public void setComparatorID(String value) {
		comparatorID = value;
		compare = true;
	}

	/*
	 * Set whether or not the application should be calling a comparator when
	 * mirroring.
	 */
	public void setCompare(boolean value) {
		compare = value;
	}

	/*
	 * Set whether or not we should ignore errors when running the mirror
	 * application.
	 */
	public void setIgnoreErrors(boolean value) {
		failOnError = !value;
	}

	/*
	 * Set whether or not the the artifacts are raw.
	 */
	public void setRaw(boolean value) {
		raw = value;
	}

	/*
	 * Set whether or not the mirror application should be run in verbose mode.
	 */
	public void setVerbose(boolean value) {
		verbose = value;
	}

	/*
	 * Set the location of the log for comparator output
	 */
	public void setComparatorLog(File comparatorLog) {
		this.comparatorLogFile = comparatorLog;
	}

	/*
	 * Set the location of the log for mirroring.
	 */
	public void setLog(File mirrorLog) {
		this.mirrorLogFile = mirrorLog;
	}

	/*
	 * Set the ArtifactMirror log
	 */
	public void setLog(IArtifactMirrorLog log) {
		mirrorLog = log;
	}

	/*
	 * Set if the artifact mirror should be validated
	 */
	public void setValidate(boolean value) {
		validate = value;
	}

	/*
	 * Set if references should be mirrored
	 */
	public void setReferences(boolean flag) {
		mirrorReferences = flag;
	}

	public void setComparatorExclusions(IQuery<IArtifactDescriptor> exclusions) {
		compareExclusions = exclusions;
	}

	public void setMirrorProperties(boolean mirrorProperties) {
		this.mirrorProperties = mirrorProperties;
	}
}
