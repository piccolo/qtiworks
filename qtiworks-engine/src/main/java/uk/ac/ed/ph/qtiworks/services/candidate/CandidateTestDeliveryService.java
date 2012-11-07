/* Copyright (c) 2012, University of Edinburgh.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice, this
 *   list of conditions and the following disclaimer in the documentation and/or
 *   other materials provided with the distribution.
 *
 * * Neither the name of the University of Edinburgh nor the names of its
 *   contributors may be used to endorse or promote products derived from this
 *   software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 *
 * This software is derived from (and contains code from) QTItools and MathAssessEngine.
 * QTItools is (c) 2008, University of Southampton.
 * MathAssessEngine is (c) 2010, University of Edinburgh.
 */
package uk.ac.ed.ph.qtiworks.services.candidate;

import uk.ac.ed.ph.qtiworks.QtiWorksLogicException;
import uk.ac.ed.ph.qtiworks.QtiWorksRuntimeException;
import uk.ac.ed.ph.qtiworks.domain.DomainEntityNotFoundException;
import uk.ac.ed.ph.qtiworks.domain.IdentityContext;
import uk.ac.ed.ph.qtiworks.domain.RequestTimestampContext;
import uk.ac.ed.ph.qtiworks.domain.dao.CandidateAttemptDao;
import uk.ac.ed.ph.qtiworks.domain.dao.CandidateSessionDao;
import uk.ac.ed.ph.qtiworks.domain.entities.AssessmentPackage;
import uk.ac.ed.ph.qtiworks.domain.entities.CandidateAttempt;
import uk.ac.ed.ph.qtiworks.domain.entities.CandidateEvent;
import uk.ac.ed.ph.qtiworks.domain.entities.CandidateEventNotification;
import uk.ac.ed.ph.qtiworks.domain.entities.CandidateFileSubmission;
import uk.ac.ed.ph.qtiworks.domain.entities.CandidateItemEventType;
import uk.ac.ed.ph.qtiworks.domain.entities.CandidateResponse;
import uk.ac.ed.ph.qtiworks.domain.entities.CandidateSession;
import uk.ac.ed.ph.qtiworks.domain.entities.CandidateTestEventType;
import uk.ac.ed.ph.qtiworks.domain.entities.Delivery;
import uk.ac.ed.ph.qtiworks.domain.entities.DeliverySettings;
import uk.ac.ed.ph.qtiworks.domain.entities.ResponseLegality;
import uk.ac.ed.ph.qtiworks.domain.entities.TestDeliverySettings;
import uk.ac.ed.ph.qtiworks.rendering.AbstractRenderingRequest;
import uk.ac.ed.ph.qtiworks.rendering.AssessmentRenderer;
import uk.ac.ed.ph.qtiworks.rendering.RenderingMode;
import uk.ac.ed.ph.qtiworks.rendering.RenderingOptions;
import uk.ac.ed.ph.qtiworks.rendering.TerminatedRenderingRequest;
import uk.ac.ed.ph.qtiworks.rendering.TestItemRenderingRequest;
import uk.ac.ed.ph.qtiworks.rendering.TestPartNavigationRenderingRequest;
import uk.ac.ed.ph.qtiworks.services.AssessmentPackageFileService;
import uk.ac.ed.ph.qtiworks.services.CandidateAuditLogger;
import uk.ac.ed.ph.qtiworks.services.CandidateDataServices;
import uk.ac.ed.ph.qtiworks.services.CandidateSessionStarter;
import uk.ac.ed.ph.qtiworks.services.EntityGraphService;
import uk.ac.ed.ph.qtiworks.services.FilespaceManager;
import uk.ac.ed.ph.qtiworks.services.domain.OutputStreamer;

import uk.ac.ed.ph.jqtiplus.internal.util.Assert;
import uk.ac.ed.ph.jqtiplus.node.AssessmentObjectType;
import uk.ac.ed.ph.jqtiplus.node.test.AssessmentTest;
import uk.ac.ed.ph.jqtiplus.notification.NotificationLevel;
import uk.ac.ed.ph.jqtiplus.notification.NotificationRecorder;
import uk.ac.ed.ph.jqtiplus.running.TestSessionController;
import uk.ac.ed.ph.jqtiplus.state.ItemSessionState;
import uk.ac.ed.ph.jqtiplus.state.TestPlanNodeKey;
import uk.ac.ed.ph.jqtiplus.state.TestSessionState;
import uk.ac.ed.ph.jqtiplus.types.FileResponseData;
import uk.ac.ed.ph.jqtiplus.types.Identifier;
import uk.ac.ed.ph.jqtiplus.types.ResponseData;
import uk.ac.ed.ph.jqtiplus.types.ResponseData.ResponseDataType;
import uk.ac.ed.ph.jqtiplus.types.StringResponseData;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Resource;

import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Service the manages the real-time delivery of a an {@link AssessmentTest}
 * to candidates.
 * <p>
 * NOTE: Remember there is no {@link IdentityContext} for candidates.
 *
 * @author David McKain
 *
 * @see CandidateSessionStarter
 */
@Service
@Transactional(propagation=Propagation.REQUIRED)
public class CandidateTestDeliveryService {

    @Resource
    private RequestTimestampContext requestTimestampContext;

    @Resource
    private CandidateAuditLogger candidateAuditLogger;

    @Resource
    private EntityGraphService entityGraphService;

    @Resource
    private AssessmentPackageFileService assessmentPackageFileService;

    @Resource
    private FilespaceManager filespaceManager;

    @Resource
    private CandidateUploadService candidateUploadService;

    @Resource
    private CandidateDataServices candidateDataServices;

    @Resource
    private AssessmentRenderer assessmentRenderer;

    @Resource
    private CandidateSessionDao candidateSessionDao;

    @Resource
    private CandidateAttemptDao candidateAttemptDao;

    //----------------------------------------------------
    // Session access

    /**
     * Looks up the {@link CandidateSession} having the given ID (xid)
     * and checks the given sessionToken against that stored in the session as a means of
     * "authentication" and that
     *
     * @param xid
     * @return
     * @throws DomainEntityNotFoundException
     * @throws CandidateForbiddenException
     * @throws CandidateCandidatePrivilegeException
     */
    public CandidateSession lookupCandidateSession(final long xid, final String sessionToken)
            throws DomainEntityNotFoundException, CandidateForbiddenException {
        Assert.notNull(sessionToken, "sessionToken");
        final CandidateSession candidateSession = candidateSessionDao.requireFindById(xid);
        if (!sessionToken.equals(candidateSession.getSessionToken())) {
            candidateAuditLogger.logAndForbid(candidateSession, CandidatePrivilege.ACCESS_CANDIDATE_SESSION);
        }
        if (candidateSession.getDelivery().getAssessment().getAssessmentType()!=AssessmentObjectType.ASSESSMENT_TEST) {
            candidateAuditLogger.logAndForbid(candidateSession, CandidatePrivilege.ACCESS_CANDIDATE_SESSION_AS_TEST);
        }
        return candidateSession;
    }

    private void ensureSessionNotTerminated(final CandidateSession candidateSession) throws CandidateForbiddenException {
        if (candidateSession.isTerminated()) {
            /* No access when session has been is closed */
            candidateAuditLogger.logAndForbid(candidateSession, CandidatePrivilege.ACCESS_TERMINATED_SESSION);
        }
    }

    //----------------------------------------------------
    // Rendering

    /**
     * Renders the current state of the {@link CandidateSession} having
     * the given ID (xid).
     */
    public void renderCurrentState(final long xid, final String sessionToken,
            final RenderingOptions renderingOptions, final OutputStreamer outputStreamer)
            throws CandidateForbiddenException, DomainEntityNotFoundException, IOException {
        final CandidateSession candidateSession = lookupCandidateSession(xid, sessionToken);
        renderCurrentState(candidateSession, renderingOptions, outputStreamer);
    }

    public void renderCurrentState(final CandidateSession candidateSession,
            final RenderingOptions renderingOptions,
            final OutputStreamer outputStreamer) throws IOException {
        Assert.notNull(candidateSession, "candidateSession");
        Assert.notNull(renderingOptions, "renderingOptions");
        Assert.notNull(outputStreamer, "outputStreamer");

        /* Look up most recent event */
        final CandidateEvent latestEvent = candidateDataServices.getMostRecentTestEvent(candidateSession);

        /* Create temporary file to hold the output before it gets streamed */
        final File resultFile = filespaceManager.createTempFile();
        try {
            /* Render to temp file */
            FileOutputStream resultOutputStream = null;
            try {
                resultOutputStream = new FileOutputStream(resultFile);
                renderEvent(latestEvent, renderingOptions, resultOutputStream);
            }
            catch (final IOException e) {
                throw new QtiWorksRuntimeException("Unexpected IOException", e);
            }
            finally {
                IOUtils.closeQuietly(resultOutputStream);
            }

            /* Finally stream to caller */
            final String contentType = renderingOptions.getSerializationMethod().getContentType();
            final long contentLength = resultFile.length();
            FileInputStream resultInputStream = null;
            try {
                resultInputStream = new FileInputStream(resultFile);
                outputStreamer.stream(contentType, contentLength, requestTimestampContext.getCurrentRequestTimestamp(),
                        resultInputStream);
            }
            catch (final FileNotFoundException e) {
                throw new QtiWorksRuntimeException("Unexpected IOException", e);
            }
            catch (final IOException e) {
                /* Streamer threw Exception */
                throw e;
            }
            finally {
                IOUtils.closeQuietly(resultInputStream);
            }
        }
        finally {
            if (!resultFile.delete()) {
                throw new QtiWorksRuntimeException("Could not delete result file " + resultFile.getPath());
            }
        }
    }

    private void renderEvent(final CandidateEvent candidateEvent,
            final RenderingOptions renderingOptions, final OutputStream resultStream) {
        switch (candidateEvent.getCategoryEventCategory()) {
            case TEST:
                renderTestEvent(candidateEvent, renderingOptions, resultStream);
                break;

            case TERMINATE:
                renderTerminated(candidateEvent, renderingOptions, resultStream);
                break;

            case ITEM:
                throw new QtiWorksLogicException("Did not expect to get an event of categroy " + candidateEvent.getCategoryEventCategory()
                        + " within a test");

            default:
                throw new QtiWorksLogicException("Unexpected logic branch. Event type " + candidateEvent.getCategoryEventCategory());
        }
    }

    private void renderTerminated(final CandidateEvent candidateEvent,
            final RenderingOptions renderingOptions, final OutputStream resultStream) {
        final CandidateSession candidateSession = candidateEvent.getCandidateSession();
        final Delivery delivery = candidateSession.getDelivery();
        final DeliverySettings deliverySettings = delivery.getDeliverySettings();
        final AssessmentPackage assessmentPackage = entityGraphService.getCurrentAssessmentPackage(delivery);

        final TerminatedRenderingRequest renderingRequest = new TerminatedRenderingRequest();
        initBaseRenderingRequest(renderingRequest, assessmentPackage, deliverySettings, renderingOptions);

        assessmentRenderer.renderTeminated(renderingRequest, resultStream);
    }

    private void renderTestEvent(final CandidateEvent candidateEvent,
            final RenderingOptions renderingOptions, final OutputStream resultStream) {
        final TestSessionState testSessionState = candidateDataServices.loadTestSessionState(candidateEvent);
        final CandidateTestEventType testEventType = candidateEvent.getTestEventType();
        switch (testEventType) {
            case INIT:
                renderAfterTestInit(candidateEvent, testSessionState, renderingOptions, resultStream);
                break;

            case ITEM_EVENT:
                renderEventWhenItemSelected(candidateEvent, testSessionState, renderingOptions, resultStream);
                break;

            case SELECT_ITEM:
                renderAfterSelectItem(candidateEvent, testSessionState, renderingOptions, resultStream);
                break;

            case SELECT_MENU:
                renderTestPartNavigationMenu(candidateEvent, testSessionState, renderingOptions, resultStream);
                break;

            default:
                throw new QtiWorksLogicException("Unexpected logic branch. Event type " + testEventType);
        }
    }

    private void renderAfterTestInit(final CandidateEvent candidateEvent,
            final TestSessionState testSessionState,
            final RenderingOptions renderingOptions, final OutputStream resultStream) {
        /* FIXME: Only supporting NONLINEAR so far, so only outcome is to show navigation menu */
        renderTestPartNavigationMenu(candidateEvent, testSessionState, renderingOptions, resultStream);
    }

    private void renderTestPartNavigationMenu(final CandidateEvent candidateEvent,
            final TestSessionState testSessionState, final RenderingOptions renderingOptions,
            final OutputStream resultStream) {
        final CandidateSession candidateSession = candidateEvent.getCandidateSession();
        final Delivery delivery = candidateSession.getDelivery();
        final TestDeliverySettings testDeliverySettings = (TestDeliverySettings) delivery.getDeliverySettings();
        final AssessmentPackage assessmentPackage = entityGraphService.getCurrentAssessmentPackage(delivery);

        final TestPartNavigationRenderingRequest renderingRequest = new TestPartNavigationRenderingRequest();
        initBaseRenderingRequest(renderingRequest, assessmentPackage, testDeliverySettings, renderingOptions);
        renderingRequest.setTestSessionState(testSessionState);

        candidateAuditLogger.logTestPartNavigationRendering(candidateEvent);
        final List<CandidateEventNotification> notifications = candidateEvent.getNotifications();
        assessmentRenderer.renderTestPartNavigation(renderingRequest, notifications, resultStream);
    }

    private void renderAfterSelectItem(final CandidateEvent candidateEvent,
            final TestSessionState testSessionState,
            final RenderingOptions renderingOptions, final OutputStream resultStream) {
        final TestPlanNodeKey currentItemKey = testSessionState.getCurrentItemKey();
        if (currentItemKey==null) {
            throw new QtiWorksLogicException("Did not expect currentItemKey==null");
        }
        final ItemSessionState itemSessionState = testSessionState.getItemSessionStates().get(currentItemKey);
        if (itemSessionState.isClosed()) {
            /* Item session closed */
            renderClosed(candidateEvent, testSessionState, itemSessionState, renderingOptions, resultStream);
        }
        else {
            /* Interacting */
            renderInteractingPresentation(candidateEvent, testSessionState, itemSessionState,
                    renderingOptions, resultStream);
        }
    }

    private void renderEventWhenItemSelected(final CandidateEvent candidateEvent,
            final TestSessionState testSessionState,
            final RenderingOptions renderingOptions, final OutputStream resultStream) {
        final TestPlanNodeKey currentItemKey = testSessionState.getCurrentItemKey();
        if (currentItemKey==null) {
            throw new QtiWorksLogicException("Did not expect currentItemKey==null");
        }
        /* Item selected, so render current state of item */
        final ItemSessionState itemSessionState = testSessionState.getItemSessionStates().get(currentItemKey);
        renderSelectedItem(candidateEvent, testSessionState, itemSessionState, renderingOptions, resultStream);
    }

    private void renderSelectedItem(final CandidateEvent candidateEvent,
            final TestSessionState testSessionState, final ItemSessionState itemSessionState,
            final RenderingOptions renderingOptions, final OutputStream resultStream) {
        if (itemSessionState.isClosed()) {
            /* Item session closed */
            renderItemEventWhenClosed(candidateEvent, testSessionState, itemSessionState, renderingOptions, resultStream);
        }
        else {
            /* Interacting */
            renderItemEventWhenInteracting(candidateEvent, testSessionState, itemSessionState, renderingOptions, resultStream);
        }
    }

    private void renderItemEventWhenInteracting(final CandidateEvent candidateEvent,
            final TestSessionState testSessionState, final ItemSessionState itemSessionState,
            final RenderingOptions renderingOptions, final OutputStream resultStream) {
        final CandidateItemEventType itemEventType = candidateEvent.getItemEventType();
        switch (itemEventType) {
            case ATTEMPT_VALID:
            case ATTEMPT_INVALID:
            case ATTEMPT_BAD:
                renderInteractingAfterAttempt(candidateEvent, testSessionState, itemSessionState,
                        renderingOptions, resultStream);
                break;

            case CLOSE:
            case PLAYBACK:
            case SOLUTION:
            case REINIT:
            case RESET:
                throw new QtiWorksLogicException("The item event " + itemEventType + " is not yet supported within tests");

            case INIT:
                throw new QtiWorksLogicException("The item event " + itemEventType + " should not occur in tests");

            default:
                throw new QtiWorksLogicException("Unexpected switch case. Event type " + itemEventType);
        }
    }

    private void renderInteractingPresentation(final CandidateEvent candidateEvent,
            final TestSessionState testSessionState, final ItemSessionState itemSessionState,
            final RenderingOptions renderingOptions, final OutputStream resultStream) {
        final TestItemRenderingRequest renderingRequest = initTestRenderingRequestWhenInteracting(candidateEvent,
                testSessionState, itemSessionState, renderingOptions, RenderingMode.AFTER_INITIALISATION);
        doRendering(candidateEvent, renderingRequest, resultStream);
    }

    private void renderInteractingAfterAttempt(final CandidateEvent candidateEvent,
            final TestSessionState testSessionState, final ItemSessionState itemSessionState,
            final RenderingOptions renderingOptions, final OutputStream resultStream) {
        final TestItemRenderingRequest renderingRequest = initTestRenderingRequestWhenInteracting(candidateEvent,
                testSessionState, itemSessionState, renderingOptions, RenderingMode.AFTER_ATTEMPT);
        fillAttemptResponseData(renderingRequest, candidateEvent);
        doRendering(candidateEvent, renderingRequest, resultStream);
    }

    private TestItemRenderingRequest initTestRenderingRequestWhenInteracting(final CandidateEvent candidateEvent,
            final TestSessionState testSessionState, final ItemSessionState itemSessionState,
            final RenderingOptions renderingOptions, final RenderingMode renderingMode) {
        final CandidateSession candidateSession = candidateEvent.getCandidateSession();
        final Delivery delivery = candidateSession.getDelivery();
//        final TestDeliverySettings testDeliverySettings = (TestDeliverySettings) delivery.getDeliverySettings();

        /* Compute current value for 'duration' */
        final double duration = computeTestSessionDuration(candidateSession);

        /* Will need to query certain parts of state */
        final TestSessionController testSessionController = candidateDataServices.createTestSessionController(delivery,
                testSessionState, new NotificationRecorder(NotificationLevel.INFO));

        final TestItemRenderingRequest renderingRequest = initTestItemRenderingRequestCustomDuration(candidateEvent,
                testSessionState, itemSessionState, renderingOptions, renderingMode, duration);
        renderingRequest.setExitTestPartAllowed(testSessionController.canExitTestPart());
//        renderingRequest.setCloseAllowed(testDeliverySettings.isAllowClose());
//        renderingRequest.setReinitAllowed(testDeliverySettings.isAllowReinitWhenInteracting());
//        renderingRequest.setResetAllowed(testDeliverySettings.isAllowResetWhenInteracting());
//        renderingRequest.setSolutionAllowed(testDeliverySettings.isAllowSolutionWhenInteracting());
//        renderingRequest.setResultAllowed(false);
//        renderingRequest.setSourceAllowed(testDeliverySettings.isAllowSource());
        return renderingRequest;
    }

    /**
     * Computes the current value for the <code>duration</code> variable for this session.
     * <p>
     * Currently, this is just the length of time since the session was first opened.
     * We DO NOT yet support breaking sessions time-wise.
     *
     * @return computed value for <code>duration</code>, which will be non-negative.
     */
    private double computeTestSessionDuration(final CandidateSession candidateSession) {
        final long startTime = candidateSession.getCreationTime().getTime();
        final long currentTime = requestTimestampContext.getCurrentRequestTimestamp().getTime();

        final double duration = (currentTime - startTime) / 1000.0;
        return duration;
    }

    private void renderItemEventWhenClosed(final CandidateEvent candidateEvent,
            final TestSessionState testSessionState, final ItemSessionState itemSessionState,
            final RenderingOptions renderingOptions,  final OutputStream resultStream) {
        final CandidateItemEventType itemEventType = candidateEvent.getItemEventType();
        switch (itemEventType) {
            case ATTEMPT_VALID:
            case ATTEMPT_INVALID:
            case ATTEMPT_BAD:
                renderClosedAfterAttempt(candidateEvent, testSessionState, itemSessionState, renderingOptions, resultStream);
                break;

            case CLOSE:
            case PLAYBACK:
            case SOLUTION:
            case REINIT:
            case RESET:
                throw new QtiWorksLogicException("The item event " + itemEventType + " is not yet supported within tests");

            case INIT:
                throw new QtiWorksLogicException("The item event " + itemEventType + " should not occur in tests");

            default:
                throw new QtiWorksLogicException("Unexpected logic branch. Event type " + itemEventType);
        }
    }

    private void renderClosedAfterAttempt(final CandidateEvent candidateEvent,
            final TestSessionState testSessionState, final ItemSessionState itemSessionState,
            final RenderingOptions renderingOptions, final OutputStream resultStream) {
        final TestItemRenderingRequest renderingRequest = initTestRenderingRequestWhenClosed(candidateEvent,
                testSessionState, itemSessionState, renderingOptions, RenderingMode.AFTER_ATTEMPT);
        fillAttemptResponseData(renderingRequest, candidateEvent);
        doRendering(candidateEvent, renderingRequest, resultStream);
    }

    private void renderClosed(final CandidateEvent candidateEvent,
            final TestSessionState testSessionState, final ItemSessionState itemSessionState,
            final RenderingOptions renderingOptions, final OutputStream resultStream) {
        final TestItemRenderingRequest renderingRequest = initTestRenderingRequestWhenClosed(candidateEvent,
                testSessionState, itemSessionState, renderingOptions, RenderingMode.CLOSED);
        doRendering(candidateEvent, renderingRequest, resultStream);
    }

    private TestItemRenderingRequest initTestRenderingRequestWhenClosed(final CandidateEvent candidateEvent,
            final TestSessionState testSessionState, final ItemSessionState itemSessionState,
            final RenderingOptions renderingOptions, final RenderingMode renderingMode) {
        final CandidateSession candidateSession = candidateEvent.getCandidateSession();
        final Delivery delivery = candidateSession.getDelivery();
//        final TestDeliverySettings testDeliverySettings = (TestDeliverySettings) delivery.getDeliverySettings();

        /* Will need to query certain parts of state */
        final TestSessionController testSessionController = candidateDataServices.createTestSessionController(delivery,
                testSessionState, new NotificationRecorder(NotificationLevel.INFO));

        final TestItemRenderingRequest renderingRequest = initTestItemRenderingRequest(candidateEvent,
                testSessionState, itemSessionState, renderingOptions, renderingMode);
        renderingRequest.setExitTestPartAllowed(testSessionController.canExitTestPart());
//        renderingRequest.setCloseAllowed(false);
//        renderingRequest.setSolutionAllowed(testDeliverySettings.isAllowSolutionWhenClosed());
//        renderingRequest.setReinitAllowed(testDeliverySettings.isAllowReinitWhenClosed());
//        renderingRequest.setResetAllowed(testDeliverySettings.isAllowResetWhenClosed());
//        renderingRequest.setResultAllowed(testDeliverySettings.isAllowResult());
//        renderingRequest.setSourceAllowed(testDeliverySettings.isAllowSource());
//
//        renderingRequest.setPlaybackAllowed(testDeliverySettings.isAllowPlayback());
//        if (testDeliverySettings.isAllowPlayback()) {
//            renderingRequest.setPlaybackEvents(getPlaybackEvents(candidateSession));
//        }
        return renderingRequest;
    }

    private TestItemRenderingRequest initTestItemRenderingRequest(final CandidateEvent candidateEvent,
            final TestSessionState testSessionState, final ItemSessionState itemSessionState,
            final RenderingOptions renderingOptions, final RenderingMode renderingMode) {
        return initTestItemRenderingRequestCustomDuration(candidateEvent, testSessionState,
                itemSessionState, renderingOptions, renderingMode, -1.0);
    }

    private TestItemRenderingRequest initTestItemRenderingRequestCustomDuration(final CandidateEvent candidateEvent,
            final TestSessionState testSessionState, final ItemSessionState itemSessionState,
            final RenderingOptions renderingOptions, final RenderingMode renderingMode,
            final double durationOverride) {
        final CandidateSession candidateSession = candidateEvent.getCandidateSession();
        final Delivery delivery = candidateSession.getDelivery();
        final TestDeliverySettings testDeliverySettings = (TestDeliverySettings) delivery.getDeliverySettings();
        final AssessmentPackage assessmentPackage = entityGraphService.getCurrentAssessmentPackage(delivery);

        /* Get System ID of current item */
        final TestPlanNodeKey currentItemKey = testSessionState.getCurrentItemKey();
        final URI itemSystemId = testSessionState.getTestPlan().getTestPlanNodeMap().get(currentItemKey).getItemSystemId();

        /* Extract ItemSessionState XML for this event and override the value for duration if caller
         * supplies a non-negative duration */
        if (durationOverride >= 0.0) {
            testSessionState.setDuration(durationOverride);
        }

        final TestItemRenderingRequest renderingRequest = new TestItemRenderingRequest();
        initBaseRenderingRequest(renderingRequest, assessmentPackage, testDeliverySettings, renderingOptions);
        renderingRequest.setAssessmentItemUri(itemSystemId);
        renderingRequest.setRenderingMode(renderingMode);
        renderingRequest.setTestSessionState(testSessionState);
        renderingRequest.setItemSessionState(itemSessionState);
        renderingRequest.setPrompt(testDeliverySettings.getPrompt());
        return renderingRequest;
    }

    private void initBaseRenderingRequest(final AbstractRenderingRequest renderingRequest,
            final AssessmentPackage assessmentPackage, final DeliverySettings deliverySettings,
            final RenderingOptions renderingOptions) {
        renderingRequest.setAssessmentResourceLocator(assessmentPackageFileService.createResolvingResourceLocator(assessmentPackage));
        renderingRequest.setAssessmentResourceUri(assessmentPackageFileService.createAssessmentObjectUri(assessmentPackage));
        renderingRequest.setAuthorMode(deliverySettings.isAuthorMode());
        renderingRequest.setRenderingOptions(renderingOptions);
    }

    private void doRendering(final CandidateEvent candidateEvent,
            final TestItemRenderingRequest renderingRequest, final OutputStream resultStream) {
        candidateAuditLogger.logTestItemRendering(candidateEvent, renderingRequest);
        final List<CandidateEventNotification> notifications = candidateEvent.getNotifications();
        assessmentRenderer.renderTestItem(renderingRequest, notifications, resultStream);
    }

    private void fillAttemptResponseData(final TestItemRenderingRequest renderingRequest, final CandidateEvent candidateEvent) {
        final CandidateAttempt attempt = candidateAttemptDao.getForEvent(candidateEvent);
        if (attempt==null) {
            throw new QtiWorksLogicException("Expected to find a CandidateAttempt corresponding to event #" + candidateEvent.getId());
        }
        fillAttemptResponseData(renderingRequest, attempt);
    }

    private void fillAttemptResponseData(final TestItemRenderingRequest renderingRequest, final CandidateAttempt candidateAttempt) {
        final Map<Identifier, ResponseData> responseDataBuilder = new HashMap<Identifier, ResponseData>();
        final Set<Identifier> badResponseIdentifiersBuilder = new HashSet<Identifier>();
        final Set<Identifier> invalidResponseIdentifiersBuilder = new HashSet<Identifier>();
        extractResponseDataForRendering(candidateAttempt, responseDataBuilder, badResponseIdentifiersBuilder, invalidResponseIdentifiersBuilder);

        renderingRequest.setResponseInputs(responseDataBuilder);
        renderingRequest.setBadResponseIdentifiers(badResponseIdentifiersBuilder);
        renderingRequest.setInvalidResponseIdentifiers(invalidResponseIdentifiersBuilder);
    }

    private void extractResponseDataForRendering(final CandidateAttempt candidateAttempt, final Map<Identifier, ResponseData> responseDataBuilder,
            final Set<Identifier> badResponseIdentifiersBuilder, final Set<Identifier> invalidResponseIdentifiersBuilder) {
        for (final CandidateResponse response : candidateAttempt.getResponses()) {
            final Identifier responseIdentifier = Identifier.parseString(response.getResponseIdentifier());
            final ResponseLegality responseLegality = response.getResponseLegality();
            final ResponseDataType responseType = response.getResponseType();
            ResponseData responseData = null;
            switch (responseType) {
                case STRING:
                    responseData = new StringResponseData(response.getStringResponseData());
                    break;

                case FILE:
                    final CandidateFileSubmission fileSubmission = response.getFileSubmission();
                    responseData = new FileResponseData(new File(fileSubmission.getStoredFilePath()),
                            fileSubmission.getContentType());
                    break;

                default:
                    throw new QtiWorksLogicException("Unexpected ResponseDataType " + responseType);
            }
            responseDataBuilder.put(responseIdentifier, responseData);
            if (responseLegality==ResponseLegality.BAD) {
                badResponseIdentifiersBuilder.add(responseIdentifier);
            }
            else if (responseLegality==ResponseLegality.INVALID) {
                invalidResponseIdentifiersBuilder.add(responseIdentifier);
            }
        }
    }

    //----------------------------------------------------
    // Attempt

    public CandidateAttempt handleAttempt(final long xid, final String sessionToken,
            final Map<Identifier, StringResponseData> stringResponseMap,
            final Map<Identifier, MultipartFile> fileResponseMap)
            throws CandidateForbiddenException, DomainEntityNotFoundException {
        final CandidateSession candidateSession = lookupCandidateSession(xid, sessionToken);
        return handleAttempt(candidateSession, stringResponseMap, fileResponseMap);
    }

    public CandidateAttempt handleAttempt(final CandidateSession candidateSession,
            final Map<Identifier, StringResponseData> stringResponseMap,
            final Map<Identifier, MultipartFile> fileResponseMap)
            throws CandidateForbiddenException {
        Assert.notNull(candidateSession, "candidateSession");

        /* Set up listener to record any notifications from JQTI candidateAuditLogger.logic */
        final NotificationRecorder notificationRecorder = new NotificationRecorder(NotificationLevel.INFO);

        /* Get current JQTI state and create JQTI controller */
        final CandidateEvent mostRecentEvent = candidateDataServices.getMostRecentTestEvent(candidateSession);
        final TestSessionController testSessionController = candidateDataServices.createTestSessionController(mostRecentEvent, notificationRecorder);
        final TestSessionState testSessionState = testSessionController.getTestSessionState();

        /* Make sure an attempt is allowed */
        if (testSessionState.getCurrentItemKey()==null || !testSessionController.canSubmitResponsesToCurrentItem()) {
            candidateAuditLogger.logAndForbid(candidateSession, CandidatePrivilege.MAKE_ATTEMPT);
        }

        final ItemSessionState itemSessionState = testSessionState.getCurrentItemSessionState();

        /* FIXME: Next wodge of code has some cut & paste! */

        /* Build response map in required format for JQTI+.
         * NB: The following doesn't test for duplicate keys in the two maps. I'm not sure
         * it's worth the effort.
         */
        final Map<Identifier, ResponseData> responseMap = new HashMap<Identifier, ResponseData>();
        if (stringResponseMap!=null) {
            for (final Entry<Identifier, StringResponseData> stringResponseEntry : stringResponseMap.entrySet()) {
                final Identifier identifier = stringResponseEntry.getKey();
                final StringResponseData stringResponseData = stringResponseEntry.getValue();
                responseMap.put(identifier, stringResponseData);
            }
        }
        final Map<Identifier, CandidateFileSubmission> fileSubmissionMap = new HashMap<Identifier, CandidateFileSubmission>();
        if (fileResponseMap!=null) {
            for (final Entry<Identifier, MultipartFile> fileResponseEntry : fileResponseMap.entrySet()) {
                final Identifier identifier = fileResponseEntry.getKey();
                final MultipartFile multipartFile = fileResponseEntry.getValue();
                final CandidateFileSubmission fileSubmission = candidateUploadService.importFileSubmission(candidateSession, multipartFile);
                final FileResponseData fileResponseData = new FileResponseData(new File(fileSubmission.getStoredFilePath()), fileSubmission.getContentType());
                responseMap.put(identifier, fileResponseData);
                fileSubmissionMap.put(identifier, fileSubmission);
            }
        }

        /* Build Map of responses in appropriate entity form */
        final CandidateAttempt candidateAttempt = new CandidateAttempt();
        final Map<Identifier, CandidateResponse> responseEntityMap = new HashMap<Identifier, CandidateResponse>();
        final Set<CandidateResponse> candidateItemResponses = new HashSet<CandidateResponse>();
        for (final Entry<Identifier, ResponseData> responseEntry : responseMap.entrySet()) {
            final Identifier responseIdentifier = responseEntry.getKey();
            final ResponseData responseData = responseEntry.getValue();

            final CandidateResponse candidateItemResponse = new CandidateResponse();
            candidateItemResponse.setResponseIdentifier(responseIdentifier.toString());
            candidateItemResponse.setAttempt(candidateAttempt);
            candidateItemResponse.setResponseType(responseData.getType());
            candidateItemResponse.setResponseLegality(ResponseLegality.VALID); /* (May change this below) */
            switch (responseData.getType()) {
                case STRING:
                    candidateItemResponse.setStringResponseData(((StringResponseData) responseData).getResponseData());
                    break;

                case FILE:
                    candidateItemResponse.setFileSubmission(fileSubmissionMap.get(responseIdentifier));
                    break;

                default:
                    throw new QtiWorksLogicException("Unexpected switch case: " + responseData.getType());
            }
            responseEntityMap.put(responseIdentifier, candidateItemResponse);
            candidateItemResponses.add(candidateItemResponse);
        }
        candidateAttempt.setResponses(candidateItemResponses);

        /* Attempt to bind responses */
        testSessionController.handleResponses(responseMap);

        /* Note any responses that failed to bind */
        final Set<Identifier> badResponseIdentifiers = itemSessionState.getBadResponseIdentifiers();
        final boolean allResponsesBound = badResponseIdentifiers.isEmpty();
        for (final Identifier badResponseIdentifier : badResponseIdentifiers) {
            responseEntityMap.get(badResponseIdentifier).setResponseLegality(ResponseLegality.BAD);
        }

        /* Now validate the responses according to any constraints specified by the interactions */
        boolean allResponsesValid = false;
        if (allResponsesBound) {
            final Set<Identifier> invalidResponseIdentifiers = itemSessionState.getInvalidResponseIdentifiers();
            allResponsesValid = invalidResponseIdentifiers.isEmpty();
            if (!allResponsesValid) {
                /* Some responses not valid, so note these down */
                for (final Identifier invalidResponseIdentifier : invalidResponseIdentifiers) {
                    responseEntityMap.get(invalidResponseIdentifier).setResponseLegality(ResponseLegality.INVALID);
                }
            }
        }

        /* Update state */
        testSessionState.setDuration(computeTestSessionDuration(candidateSession));

        /* Record resulting attempt and event */
        final CandidateItemEventType itemEventType = allResponsesBound ?
            (allResponsesValid ? CandidateItemEventType.ATTEMPT_VALID : CandidateItemEventType.ATTEMPT_INVALID)
            : CandidateItemEventType.ATTEMPT_BAD;
        final CandidateEvent candidateEvent = candidateDataServices.recordCandidateTestEvent(candidateSession,
                CandidateTestEventType.ITEM_EVENT, itemEventType, testSessionState, notificationRecorder);

        candidateAttempt.setEvent(candidateEvent);
        candidateAttemptDao.persist(candidateAttempt);

        /* Log this (in existing state) */
        candidateAuditLogger.logTestItemCandidateAttempt(candidateSession, candidateAttempt);

        /* Persist session */
        candidateSessionDao.update(candidateSession);
        return candidateAttempt;
    }

    //----------------------------------------------------
    // Navigation

    public CandidateSession selectNavigationMenu(final long xid, final String sessionToken)
            throws CandidateForbiddenException, DomainEntityNotFoundException {
        final CandidateSession candidateSession = lookupCandidateSession(xid, sessionToken);
        return selectNavigationMenu(candidateSession);
    }

    public CandidateSession selectNavigationMenu(final CandidateSession candidateSession)
            throws CandidateForbiddenException {
        Assert.notNull(candidateSession, "candidateSession");

        /* Get current session state */
        final TestSessionState testSessionState = candidateDataServices.computeCurrentTestSessionState(candidateSession);

        /* Make sure caller may do this */
        ensureSessionNotTerminated(candidateSession);

        /* FIXME: Probably have further checks to do here? */

        /* Update state */
        final NotificationRecorder notificationRecorder = new NotificationRecorder(NotificationLevel.INFO);
        final Delivery delivery = candidateSession.getDelivery();
        final TestSessionController testSessionController = candidateDataServices.createTestSessionController(delivery,
                testSessionState, notificationRecorder);
        testSessionController.selectItem(null);

        /* Record and log event */
        final CandidateEvent candidateEvent = candidateDataServices.recordCandidateTestEvent(candidateSession,
                CandidateTestEventType.SELECT_MENU, testSessionState, notificationRecorder);
        candidateAuditLogger.logCandidateEvent(candidateSession, candidateEvent);

        return candidateSession;
    }


    public CandidateSession selectItem(final long xid, final String sessionToken, final TestPlanNodeKey itemKey)
            throws CandidateForbiddenException, DomainEntityNotFoundException {
        final CandidateSession candidateSession = lookupCandidateSession(xid, sessionToken);
        return selectItem(candidateSession, itemKey);
    }

    public CandidateSession selectItem(final CandidateSession candidateSession, final TestPlanNodeKey itemKey)
            throws CandidateForbiddenException {
        Assert.notNull(candidateSession, "candidateSession");
        Assert.notNull(itemKey, "key");

        /* Get current session state */
        final TestSessionState testSessionState = candidateDataServices.computeCurrentTestSessionState(candidateSession);

        /* Make sure caller may do this */
        ensureSessionNotTerminated(candidateSession);

        /* Update state */
        final NotificationRecorder notificationRecorder = new NotificationRecorder(NotificationLevel.INFO);
        final Delivery delivery = candidateSession.getDelivery();
        final TestSessionController testSessionController = candidateDataServices.createTestSessionController(delivery,
                testSessionState, notificationRecorder);
        testSessionController.selectItem(itemKey);

        /* Record and log event */
        final CandidateEvent candidateTestEvent = candidateDataServices.recordCandidateTestEvent(candidateSession,
                CandidateTestEventType.SELECT_ITEM, null, itemKey, testSessionState, notificationRecorder);
        candidateAuditLogger.logCandidateEvent(candidateSession, candidateTestEvent);

        return candidateSession;
    }
}