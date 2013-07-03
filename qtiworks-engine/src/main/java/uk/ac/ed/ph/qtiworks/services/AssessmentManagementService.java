/* Copyright (c) 2012-2013, University of Edinburgh.
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
package uk.ac.ed.ph.qtiworks.services;

import uk.ac.ed.ph.qtiworks.QtiWorksLogicException;
import uk.ac.ed.ph.qtiworks.QtiWorksRuntimeException;
import uk.ac.ed.ph.qtiworks.domain.DomainConstants;
import uk.ac.ed.ph.qtiworks.domain.DomainEntityNotFoundException;
import uk.ac.ed.ph.qtiworks.domain.Privilege;
import uk.ac.ed.ph.qtiworks.domain.PrivilegeException;
import uk.ac.ed.ph.qtiworks.domain.entities.Assessment;
import uk.ac.ed.ph.qtiworks.domain.entities.AssessmentPackage;
import uk.ac.ed.ph.qtiworks.domain.entities.CandidateSession;
import uk.ac.ed.ph.qtiworks.domain.entities.Delivery;
import uk.ac.ed.ph.qtiworks.domain.entities.DeliverySettings;
import uk.ac.ed.ph.qtiworks.domain.entities.DeliveryType;
import uk.ac.ed.ph.qtiworks.domain.entities.ItemDeliverySettings;
import uk.ac.ed.ph.qtiworks.domain.entities.LtiContext;
import uk.ac.ed.ph.qtiworks.domain.entities.LtiResource;
import uk.ac.ed.ph.qtiworks.domain.entities.TestDeliverySettings;
import uk.ac.ed.ph.qtiworks.domain.entities.User;
import uk.ac.ed.ph.qtiworks.domain.entities.UserRole;
import uk.ac.ed.ph.qtiworks.services.base.AuditLogger;
import uk.ac.ed.ph.qtiworks.services.base.IdentityService;
import uk.ac.ed.ph.qtiworks.services.base.ServiceUtilities;
import uk.ac.ed.ph.qtiworks.services.dao.AssessmentDao;
import uk.ac.ed.ph.qtiworks.services.dao.AssessmentPackageDao;
import uk.ac.ed.ph.qtiworks.services.dao.CandidateSessionDao;
import uk.ac.ed.ph.qtiworks.services.dao.DeliveryDao;
import uk.ac.ed.ph.qtiworks.services.dao.DeliverySettingsDao;
import uk.ac.ed.ph.qtiworks.services.domain.AssessmentLtiOutcomesSettingsTemplate;
import uk.ac.ed.ph.qtiworks.services.domain.AssessmentMetadataTemplate;
import uk.ac.ed.ph.qtiworks.services.domain.AssessmentPackageFileImportException;
import uk.ac.ed.ph.qtiworks.services.domain.AssessmentStateException;
import uk.ac.ed.ph.qtiworks.services.domain.AssessmentStateException.APSFailureReason;
import uk.ac.ed.ph.qtiworks.services.domain.DeliveryTemplate;
import uk.ac.ed.ph.qtiworks.services.domain.ItemDeliverySettingsTemplate;
import uk.ac.ed.ph.qtiworks.services.domain.TestDeliverySettingsTemplate;

import uk.ac.ed.ph.jqtiplus.internal.util.Assert;
import uk.ac.ed.ph.jqtiplus.internal.util.StringUtilities;
import uk.ac.ed.ph.jqtiplus.node.AssessmentObjectType;
import uk.ac.ed.ph.jqtiplus.node.outcome.declaration.OutcomeDeclaration;
import uk.ac.ed.ph.jqtiplus.validation.AssessmentObjectValidationResult;
import uk.ac.ed.ph.jqtiplus.xperimental.ToRefactor;

import java.io.File;
import java.io.InputStream;
import java.util.List;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.Validator;
import org.springframework.web.multipart.MultipartFile;

/**
 * Top layer services for *managing* {@link Assessment}s and related entities.
 *
 * @author David McKain
 */
@Service
@Transactional(readOnly=false, propagation=Propagation.REQUIRED)
public class AssessmentManagementService {

    private static final Logger logger = LoggerFactory.getLogger(AssessmentManagementService.class);

    public static final String DEFAULT_IMPORT_TITLE = "My Assessment";

    @Resource
    private AuditLogger auditLogger;

    @Resource
    private IdentityService identityService;

    @Resource
    private FilespaceManager filespaceManager;

    @Resource
    private Validator jsr303Validator;

    @Resource
    private AssessmentDataService assessmentDataService;

    @Resource
    private AssessmentPackageFileService assessmentPackageFileService;

    @Resource
    private DataDeletionService dataDeletionService;

    @Resource
    private AssessmentPackageImporter assessmentPackageFileImporter;

    @Resource
    private AssessmentDao assessmentDao;

    @Resource
    private AssessmentPackageDao assessmentPackageDao;

    @Resource
    private DeliveryDao deliveryDao;

    @Resource
    private DeliverySettingsDao deliverySettingsDao;

    @Resource
    private CandidateSessionDao candidateSessionDao;

    //-------------------------------------------------
    // Assessment access

    /**
     * Looks up the {@link Assessment} having the given ID (aid) and checks that
     * the caller may manage it.
     */
    public Assessment lookupAssessment(final long aid)
            throws DomainEntityNotFoundException, PrivilegeException {
        final Assessment result = assessmentDao.requireFindById(aid);
        ensureCallerMayManage(result);
        return result;
    }

    private User ensureCallerMayManage(final Assessment assessment)
            throws PrivilegeException {
        final User caller = identityService.getCurrentThreadUser();
        final LtiResource ltiResource = identityService.getCurrentThreadLtiResource();
        if (ltiResource!=null) {
            /* Manager access is shared with all instructors in the LTI context */
            final LtiContext ltiContext = ltiResource.getLtiContext();
            final LtiContext assessmentLtiContext = assessment.getOwnerLtiContext();
            if (!caller.isInstructor() || assessmentLtiContext==null || !ltiContext.equals(assessmentLtiContext)) {
                throw new PrivilegeException(caller, Privilege.MANAGE_ASSESSMENT, assessment);
            }
        }
        else if (!assessment.getOwnerUser().equals(caller)) {
            /* If not LTI context, then assessments are private to owner */
            throw new PrivilegeException(caller, Privilege.MANAGE_ASSESSMENT, assessment);
        }
        return caller;
    }

    //-------------------------------------------------

    /**
     * Creates and persists a new {@link Assessment} and initial {@link AssessmentPackage}
     * from the data provided by the given {@link InputStream} and having the given content type.
     * <p>
     * Validation can be invoked immediately, otherwise callers will want to call
     * {@link #validateAssessment(Assessment)} at a later point, certainly before
     * trying to run this new {@link Assessment}.
     * <p>
     * Success post-conditions:
     * - a new {@link AssessmentPackage} is persisted, and its data is safely stored in a sandbox
     *
     * @param multipartFile data to be imported
     * @param validate whether to validate the resulting {@link AssessmentPackage} immediately.
     *
     * @return newly created {@link Assessment} entity
     *
     * @throws PrivilegeException if the caller is not allowed to perform this action
     * @throws AssessmentPackageFileImportException
     */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Assessment importAssessment(final MultipartFile multipartFile, final boolean validate)
            throws PrivilegeException, AssessmentPackageFileImportException {
        Assert.notNull(multipartFile, "multipartFile");
        final User caller = ensureCallerMayCreateAssessment();

        /* First, upload the data into a sandbox */
        final AssessmentPackage assessmentPackage = importPackageFiles(multipartFile);
        final Assessment assessment;
        try {
            /* Persist new AssessmentPackage (before linking to Assessment) */
            assessmentPackage.setImportVersion(Long.valueOf(1L));
            assessmentPackageDao.persist(assessmentPackage);

            /* Maybe validate AssessmentPackage */
            if (validate) {
                assessmentDataService.validateAssessmentPackage(assessmentPackage);
            }

            /* Create resulting Assessment entity */
            assessment = new Assessment();
            assessment.setAssessmentType(assessmentPackage.getAssessmentType());
            assessment.setOwnerUser(caller);

            final LtiResource currentLtiResource = identityService.getCurrentThreadLtiResource();
            if (currentLtiResource!=null) {
                assessment.setOwnerLtiContext(currentLtiResource.getLtiContext());
            }

            final String fileName = multipartFile.getOriginalFilename();
            String assessmentName;
            if (StringUtilities.isNullOrBlank(fileName)) {
                assessmentName = assessmentPackage.getAssessmentType()==AssessmentObjectType.ASSESSMENT_ITEM ? "Item" : "Test";
            }
            else {
                assessmentName = ServiceUtilities.trimString(fileName, DomainConstants.ASSESSMENT_NAME_MAX_LENGTH);
            }
            assessment.setName(assessmentName);

            /* Guess a title */
            final String guessedTitle = assessmentPackageFileService.guessAssessmentTitle(assessmentPackage);
            final String resultingTitle = !StringUtilities.isNullOrEmpty(guessedTitle) ? guessedTitle : DEFAULT_IMPORT_TITLE;
            assessment.setTitle(ServiceUtilities.trimSentence(resultingTitle, DomainConstants.ASSESSMENT_TITLE_MAX_LENGTH));

            /* Relate Assessment & AssessmentPackage */
            assessment.setSelectedAssessmentPackage(assessmentPackage);
            assessment.setPackageImportVersion(Long.valueOf(1L));
            assessmentPackage.setAssessment(assessment);

            /* Persist/relate entities */
            assessmentDao.persist(assessment);
            assessmentPackageDao.update(assessmentPackage);
        }
        catch (final Exception e) {
            logger.warn("Failed to save new Assessment or AssessmentPackage - deleting sandbox");
            deleteAssessmentPackageSandbox(assessmentPackage);
            throw new QtiWorksRuntimeException("Failed to persist Assessment/AssessmentPackage {}", e);
        }

        logger.debug("Created new Assessment #{} with package #{}", assessment.getId(), assessmentPackage.getId());
        auditLogger.recordEvent("Created Assessment #" + assessment.getId() + " and AssessmentPackage #" + assessmentPackage.getId());
        return assessment;
    }

    /**
     * We are currently allowing INSTRUCTOR and ANONYMOUS (demo)
     * users to create assignments.
     */
    private User ensureCallerMayCreateAssessment() throws PrivilegeException {
        final User caller = identityService.getCurrentThreadUser();
        final UserRole userRole = caller.getUserRole();
        if (!(userRole==UserRole.ANONYMOUS || userRole==UserRole.INSTRUCTOR)) {
            throw new PrivilegeException(caller, Privilege.CREATE_ASSESSMENT);
        }
        return caller;
    }

    /**
     * Deletes the {@link Assessment} having the given aid and owned by the caller.
     * <p>
     * NOTE: This deletes ALL associated data, including candidate data. Use with care!
     */
    public void deleteAssessment(final long aid)
            throws DomainEntityNotFoundException, PrivilegeException {
        /* Look up assessment and check permissions */
        final Assessment assessment = lookupAssessment(aid);

        /* Now delete it and all associated data */
        dataDeletionService.deleteAssessment(assessment);

        /* Log what happened */
        logger.debug("Deleted Assessment #{}", assessment.getId());
        auditLogger.recordEvent("Deleted Assessment #" + assessment.getId());
    }

    /**
     * Updates the basic metadata/properties for the {@link Assessment} having the given ID (aid).
     */
    public Assessment updateAssessmentMetadata(final long aid, final AssessmentMetadataTemplate template)
            throws BindException, DomainEntityNotFoundException, PrivilegeException {
        /* Validate data */
        Assert.notNull(template, "command");
        final BeanPropertyBindingResult errors = new BeanPropertyBindingResult(template, "assessmentMetadataTemplate");
        jsr303Validator.validate(template, errors);
        if (errors.hasErrors()) {
            throw new BindException(errors);
        }

        /* Look up Assessment */
        final Assessment assessment = assessmentDao.requireFindById(aid);
        ensureCallerMayManage(assessment);

        /* Make changes */
        assessment.setName(template.getName().trim());
        assessment.setTitle(template.getTitle().trim());
        assessmentDao.update(assessment);
        return assessment;
    }

    /**
     * Updates the LTI outcomes settings for the {@link Assessment} having the given ID (aid).
     */
    public Assessment updateAssessmentLtiOutcomesSettings(final long aid, final AssessmentLtiOutcomesSettingsTemplate template)
            throws BindException, DomainEntityNotFoundException, PrivilegeException {
        /* Validate data */
        Assert.notNull(template, "template");
        final BeanPropertyBindingResult errors = new BeanPropertyBindingResult(template, "assessmentLtiOutcomesSettingsTemplate");
        jsr303Validator.validate(template, errors);
        if (errors.hasErrors()) {
            throw new BindException(errors);
        }

        /* Look up Assessment */
        final Assessment assessment = assessmentDao.requireFindById(aid);
        ensureCallerMayManage(assessment);

        /* Perform further validation by checking that the outcomeVariable matches one declared in the Assessment XML */
        final String resultOutcomeIdentifier = template.getResultOutcomeIdentifier();
        final List<OutcomeDeclaration> outcomeVariableDeclarations = assessmentDataService.getOutcomeVariableDeclarations(assessment);
        if (outcomeVariableDeclarations==null) {
            errors.reject("assessmentLtiOutcomesSettingsTemplate.invalid", resultOutcomeIdentifier);
            throw new BindException(errors);
        }
        boolean found = false;
        for (final OutcomeDeclaration outcomeDeclaration : outcomeVariableDeclarations) {
            if (resultOutcomeIdentifier.equals(outcomeDeclaration.getIdentifier().toString())) {
                found = true;
                break;
            }
        }
        if (!found) {
            errors.reject("assessmentLtiOutcomesSettingsTemplate.notFound", resultOutcomeIdentifier);
            throw new BindException(errors);
        }

        /* Finally record the changes */
        assessment.setLtiResultOutcomeIdentifier(template.getResultOutcomeIdentifier());
        assessment.setLtiResultMinimum(template.getResultMinimum());
        assessment.setLtiResultMaximum(template.getResultMaximum());
        assessmentDao.update(assessment);
        return assessment;
    }

    /**
     * Imports a new {@link AssessmentPackage}, making it the selected one for
     * the given {@link Assessment}. Any existing {@link AssessmentPackage}s
     * will be deleted.
     * <p>
     * Any non-terminated {@link CandidateSession}s running on the {@link Assessment} will
     * be terminated.
     * <p>
     * The new {@link AssessmentPackage} must be of the same type as the
     * {@link Assessment}. I.e. it is not possible to replace an item with
     * a test, or a test with an item.
     */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Assessment replaceAssessmentPackage(final long aid,
            final MultipartFile multipartFile)
            throws AssessmentStateException, PrivilegeException,
            AssessmentPackageFileImportException, DomainEntityNotFoundException {
        Assert.notNull(multipartFile, "multipartFile");
        final Assessment assessment = assessmentDao.requireFindById(aid);
        ensureCallerMayManage(assessment);
        final AssessmentPackage oldPackage = assessment.getSelectedAssessmentPackage();

        /* Upload data into a new sandbox */
        final AssessmentPackage newAssessmentPackage = importPackageFiles(multipartFile);

        /* Make sure we haven't gone item->test or test->item */
        if (newAssessmentPackage.getAssessmentType()!=assessment.getAssessmentType()) {
            throw new AssessmentStateException(APSFailureReason.CANNOT_CHANGE_ASSESSMENT_TYPE,
                    assessment.getAssessmentType(), newAssessmentPackage.getAssessmentType());
        }

        /* Terminate any outstanding CandidateSessions on this Assessment */
        final int terminatedSessions = terminateCandidateSessions(assessment);

        /* Join Assessment to new package */
        final long newPackageVersion = assessment.getPackageImportVersion().longValue() + 1;
        assessment.setPackageImportVersion(newPackageVersion);
        assessment.setSelectedAssessmentPackage(newAssessmentPackage);
        newAssessmentPackage.setAssessment(assessment);
        newAssessmentPackage.setImportVersion(newPackageVersion);

        /* Now update DB */
        try {
            assessmentDao.update(assessment);
            assessmentPackageDao.persist(newAssessmentPackage);
        }
        catch (final Exception e) {
            logger.warn("Failed to update state of AssessmentPackage {} after file replacement - deleting new sandbox", e);
            deleteAssessmentPackageSandbox(newAssessmentPackage);
            throw new QtiWorksRuntimeException("Failed to update AssessmentPackage entity " + assessment, e);
        }

        /* Finally delete the old package (if applicable) */
        if (oldPackage!=null) {
            dataDeletionService.deleteAssessmentPackage(oldPackage);
        }

        logger.debug("Updated Assessment #{} to have package #{}, terminating {} CandidateSession(s)",
                new Object[] { assessment.getId(), newAssessmentPackage.getId(), terminatedSessions });
        auditLogger.recordEvent("Updated Assessment #" + assessment.getId()
                + " with AssessmentPackage #" + newAssessmentPackage.getId()
                + ", terminating " + terminatedSessions + " session(s)");
        return assessment;
    }

    public AssessmentObjectValidationResult<?> validateAssessment(final long aid)
            throws PrivilegeException, DomainEntityNotFoundException {
        final Assessment assessment = lookupAssessment(aid);
        return assessmentDataService.validateAssessment(assessment);
    }

    //-------------------------------------------------
    // Basic CRUD for DeliverySettings

    public DeliverySettings lookupDeliverySettings(final long dsid)
            throws DomainEntityNotFoundException, PrivilegeException {
        final DeliverySettings deliverySettings = deliverySettingsDao.requireFindById(dsid);
        ensureCallerMayManage(deliverySettings);
        return deliverySettings;
    }

    public DeliverySettings lookupAndMatchDeliverySettings(final long dsid, final Assessment assessment)
            throws DomainEntityNotFoundException, PrivilegeException {
        final DeliverySettings deliverySettings = deliverySettingsDao.requireFindById(dsid);
        ensureCallerMayManage(deliverySettings);
        ensureCompatible(deliverySettings, assessment);
        return deliverySettings;
    }

    private User ensureCallerMayManage(final DeliverySettings deliverySettings)
            throws PrivilegeException {
        final User caller = identityService.getCurrentThreadUser();
        final LtiResource ltiResource = identityService.getCurrentThreadLtiResource();
        if (ltiResource!=null) {
            /* Manager access is shared with all instructors in the LTI context */
            final LtiContext ltiContext = ltiResource.getLtiContext();
            final LtiContext dsLtiContext = deliverySettings.getOwnerLtiContext();
            if (!caller.isInstructor() || dsLtiContext==null || !ltiContext.equals(dsLtiContext)) {
                throw new PrivilegeException(caller, Privilege.MANAGE_DELIVERY_SETTINGS, deliverySettings);
            }
        }
        else if (!deliverySettings.getOwnerUser().equals(caller)) {
            /* If not LTI context, then assessments are private to owner */
            throw new PrivilegeException(caller, Privilege.MANAGE_DELIVERY_SETTINGS, deliverySettings);
        }
        return caller;
    }

    private User ensureCallerMayCreateDeliverySettings() throws PrivilegeException {
        final User caller = identityService.getCurrentThreadUser();
        if (caller.getUserRole()!=UserRole.INSTRUCTOR) {
            throw new PrivilegeException(caller, Privilege.CREATE_DELIVERY_SETTINGS);
        }
        return caller;
    }

    //-------------------------------------------------
    // CRUD for ItemDeliverySettings

    public ItemDeliverySettings lookupItemDeliverySettings(final long dsid)
            throws DomainEntityNotFoundException, PrivilegeException {
        final DeliverySettings deliverySettings = deliverySettingsDao.requireFindById(dsid);
        ensureCallerMayManage(deliverySettings);
        ensureCompatible(deliverySettings, AssessmentObjectType.ASSESSMENT_ITEM);

        return (ItemDeliverySettings) deliverySettings;
    }

    public ItemDeliverySettings createItemDeliverySettings(final ItemDeliverySettingsTemplate template)
            throws PrivilegeException, BindException {
        /* Check caller privileges */
        final User caller = ensureCallerMayCreateDeliverySettings();

        /* Validate template */
        validateItemDeliverySettingsTemplate(template);

        /* Create and persist new settings from template */
        final ItemDeliverySettings result = new ItemDeliverySettings();
        assessmentDataService.mergeItemDeliverySettings(template, result);

        /* Set ownership and LTI context (if specified) */
        result.setOwnerUser(caller);
        final LtiResource currentLtiResource = identityService.getCurrentThreadLtiResource();
        if (currentLtiResource!=null) {
            result.setOwnerLtiContext(currentLtiResource.getLtiContext());
        }

        deliverySettingsDao.persist(result);

        auditLogger.recordEvent("Created ItemDeliverySettings #" + result.getId());
        return result;
    }

    private void validateItemDeliverySettingsTemplate(final ItemDeliverySettingsTemplate template)
            throws BindException {
        Assert.notNull(template, "template");
        final BeanPropertyBindingResult errors = new BeanPropertyBindingResult(template, "itemDeliverySettingsTemplate");
        jsr303Validator.validate(template, errors);
        if (errors.hasErrors()) {
            throw new BindException(errors);
        }
    }

    public ItemDeliverySettings updateItemDeliverySettings(final long dsid, final ItemDeliverySettingsTemplate template)
            throws PrivilegeException, DomainEntityNotFoundException, BindException {
        /* Check caller privileges */
        final ItemDeliverySettings itemDeliverySettings = lookupItemDeliverySettings(dsid);
        ensureCallerMayManage(itemDeliverySettings);

        /* Validate template */
        validateItemDeliverySettingsTemplate(template);

        /* Merge template into options and update */
        assessmentDataService.mergeItemDeliverySettings(template, itemDeliverySettings);
        deliverySettingsDao.update(itemDeliverySettings);

        auditLogger.recordEvent("Updated ItemDeliverySettings #" + itemDeliverySettings.getId());
        return itemDeliverySettings;
    }

    //-------------------------------------------------
    // CRUD for TestDeliverySettings

    public TestDeliverySettings lookupTestDeliverySettings(final long dsid)
            throws DomainEntityNotFoundException, PrivilegeException {
        final DeliverySettings deliverySettings = deliverySettingsDao.requireFindById(dsid);
        ensureCallerMayManage(deliverySettings);
        ensureCompatible(deliverySettings, AssessmentObjectType.ASSESSMENT_TEST);

        return (TestDeliverySettings) deliverySettings;
    }

    public TestDeliverySettings createTestDeliverySettings(final TestDeliverySettingsTemplate template)
            throws PrivilegeException, BindException {
        /* Check caller privileges */
        final User caller = ensureCallerMayCreateDeliverySettings();

        /* Validate template */
        validateTestDeliverySettingsTemplate(template);

        /* Create and persist new settings from template */
        final TestDeliverySettings result = new TestDeliverySettings();
        assessmentDataService.mergeTestDeliverySettings(template, result);

        /* Set ownership LTI context (if specified) */
        result.setOwnerUser(caller);
        final LtiResource currentLtiResource = identityService.getCurrentThreadLtiResource();
        if (currentLtiResource!=null) {
            result.setOwnerLtiContext(currentLtiResource.getLtiContext());
        }
        deliverySettingsDao.persist(result);

        auditLogger.recordEvent("Created TestDeliverySettings #" + result.getId());
        return result;
    }

    private void validateTestDeliverySettingsTemplate(final TestDeliverySettingsTemplate template)
            throws BindException {
        Assert.notNull(template, "template");
        final BeanPropertyBindingResult errors = new BeanPropertyBindingResult(template, "testDeliverySettingsTemplate");
        jsr303Validator.validate(template, errors);
        if (errors.hasErrors()) {
            throw new BindException(errors);
        }
    }

    public TestDeliverySettings updateTestDeliverySettings(final long dsid, final TestDeliverySettingsTemplate template)
            throws PrivilegeException, DomainEntityNotFoundException, BindException {
        /* Check caller privileges */
        final TestDeliverySettings testDeliverySettings = lookupTestDeliverySettings(dsid);
        ensureCallerMayManage(testDeliverySettings);

        /* Validate template */
        validateTestDeliverySettingsTemplate(template);

        /* Merge template into options and update */
        assessmentDataService.mergeTestDeliverySettings(template, testDeliverySettings);
        deliverySettingsDao.update(testDeliverySettings);

        auditLogger.recordEvent("Updated TestDeliverySettings #" + testDeliverySettings.getId());
        return testDeliverySettings;
    }

    //-------------------------------------------------

    /**
     * Safely deletes the {@link DeliverySettings} having the given ID (dsid), updating any
     * existing {@link Delivery} entities using it so that they use default (i.e. null)
     * {@link DeliverySettings}.
     *
     * @return the number of {@link Delivery} entities changed by this action
     */
    public int deleteDeliverySettings(final long dsid)
            throws DomainEntityNotFoundException, PrivilegeException {
        /* Look up entity and check permissions */
        final DeliverySettings deliverySettings = deliverySettingsDao.requireFindById(dsid);
        ensureCallerMayManage(deliverySettings);

        /* Update any Deliveries using these settings so that they revert to defaults */
        final List<Delivery> deliveriesUsingSettings = deliveryDao.getUsingSettings(deliverySettings);
        final int deliveriesAffected = deliveriesUsingSettings.size();
        for (final Delivery delivery : deliveriesUsingSettings) {
            delivery.setDeliverySettings(null);
            deliveryDao.update(delivery);
        }

        /* Delete DS entity */
        deliverySettingsDao.remove(deliverySettings);

        /* Log what happened */
        logger.debug("Deleted DeliverySettings #{}, affecting {} Delivery/ies", deliverySettings.getId(), deliveriesAffected);
        auditLogger.recordEvent("Deleted DeliverySettings #" + deliverySettings.getId() + ", affecting " + deliveriesAffected + "Delivery/ies");
        return deliveriesAffected;
    }

    //-------------------------------------------------
    // LTIResource configuration

    /**
     * Selects the {@link Assessment} to be associated with the current {@link LtiResource}
     * (when appropriate).
     * <p>
     * Any {@link CandidateSession}s running on the existing {@link Assessment} (if used) will
     * be terminated in the process.
     *
     * @param aid ID (aid) of the {@link Assessment} to select.
     */
    public void selectCurrentLtiResourceAssessment(final long aid)
            throws DomainEntityNotFoundException, PrivilegeException {
        /* Look up and check access on requested Assessment */
        final LtiResource currentLtiResource = identityService.ensureCurrentThreadLtiResource();
        final Assessment newAssessment = lookupAssessment(aid);

        /* Terminate any candidate sessions on the currently Associated assessment (if appropriate) */
        final Delivery delivery = currentLtiResource.getDelivery();
        final Assessment oldAssessment = delivery.getAssessment();
        int terminatedSessions = 0;
        if (oldAssessment!=null) {
            terminatedSessions = terminateCandidateSessions(oldAssessment);
        }

        /* Set up link between Delivery and Assessment */
        delivery.setAssessment(newAssessment);
        deliveryDao.update(delivery);

        logger.debug("Assessment for LTI Delivery #{} has been set to #{}, terminating {} CandidateSession(s) associated to the original Assessment",
                new Object[] { delivery.getId(), newAssessment.getId(), terminatedSessions });
        auditLogger.recordEvent("Assessment for LTI Delivery #" + delivery.getId()
                + " has been set to #" + newAssessment.getId()
                + ", terminating " + terminatedSessions
                + " CandidateSession(s) associated to the original Assessment");
    }

    public void selectCurrentLtiResourceDeliverySettings(final long dsid)
            throws DomainEntityNotFoundException, PrivilegeException {
        /* Look up and check access on requested Delivery Settings */
        final LtiResource currentLtiResource = identityService.ensureCurrentThreadLtiResource();
        final Delivery delivery = currentLtiResource.getDelivery();
        final DeliverySettings deliverySettings = lookupDeliverySettings(dsid);

        /* Set up link between Delivery and DeliverySettings */
        delivery.setDeliverySettings(deliverySettings);
        deliveryDao.update(delivery);

        logger.debug("DeliverySettings for LTI Delivery #{} have been set to #{}", delivery.getId(), deliverySettings.getId());
        auditLogger.recordEvent("DeliverySettings for LTI Delivery #" + delivery.getId()
                + " have been set to #" + deliverySettings.getId());
    }

    //-------------------------------------------------
    // CRUD for Delivery
    // (access controls are governed by owning Assessment)

    public Delivery lookupDelivery(final long did)
            throws DomainEntityNotFoundException, PrivilegeException {
        final Delivery delivery = deliveryDao.requireFindById(did);
        ensureCallerMayManage(delivery.getAssessment());
        return delivery;
    }

    /** Creates a new {@link Delivery} for the given Assignment using reasonable default values */
    @ToRefactor
    public Delivery createDelivery(final long aid)
            throws PrivilegeException, DomainEntityNotFoundException {
        /* Look up Assessment and check privs */
        final Assessment assessment = lookupAssessment(aid);
        ensureCallerMayManage(assessment);

        /* Create Delivery template with reasonable defaults */
        final DeliveryTemplate template = new DeliveryTemplate();
        final long existingDeliveryCount = assessmentDataService.countUserCreatedDeliveries(assessment);
        template.setTitle("Delivery #" + (existingDeliveryCount+1));
        template.setDsid(null);
        template.setOpen(false);
        template.setLtiEnabled(false);

        /* Create and return new entity */
        return createDelivery(assessment, null, template);
    }

    @ToRefactor
    public Delivery createDelivery(final long aid, final DeliveryTemplate template)
            throws PrivilegeException, DomainEntityNotFoundException, BindException {
        /* Validate template */
        validateDeliveryTemplate(template);

        /* Look up Assessment and check caller and change it */
        final Assessment assessment = lookupAssessment(aid);
        ensureCallerMayManage(assessment);

        /* Look up settings and check privileges */
        final Long dsid = template.getDsid();
        DeliverySettings deliverySettings = null;
        if (dsid!=null) {
            deliverySettings = lookupAndMatchDeliverySettings(dsid.longValue(), assessment);
        }

        /* Create and return new entity */
        return createDelivery(assessment, deliverySettings, template);
    }

    private Delivery createDelivery(final Assessment assessment,
            final DeliverySettings deliverySettings, final DeliveryTemplate template) {
        final Delivery delivery = new Delivery();
        delivery.setAssessment(assessment);
        delivery.setDeliverySettings(deliverySettings);
        delivery.setDeliveryType(DeliveryType.USER_CREATED);
        delivery.setOpen(template.isOpen());
        delivery.setLtiEnabled(template.isLtiEnabled());
        delivery.setTitle(template.getTitle().trim());
        delivery.setLtiConsumerKeyToken(ServiceUtilities.createRandomAlphanumericToken(DomainConstants.LTI_SECRET_LENGTH));
        delivery.setLtiConsumerSecret(ServiceUtilities.createRandomAlphanumericToken(DomainConstants.LTI_SECRET_LENGTH));
        deliveryDao.persist(delivery);
        return delivery;
    }

    /**
     * Deletes the {@link Delivery} having the given did and owned by the caller.
     *
     * NOTE: This deletes ALL associated data, including candidate data. Use with care!
     */
    public Assessment deleteDelivery(final long did)
            throws DomainEntityNotFoundException, PrivilegeException {
        /* Look up assessment and check permissions */
        final Delivery delivery = deliveryDao.requireFindById(did);
        final Assessment assessment = delivery.getAssessment();
        ensureCallerMayManage(assessment);

        /* Now delete it and all associated data */
        dataDeletionService.deleteDelivery(delivery);

        /* Log what happened */
        logger.debug("Deleted Delivery #{}", did);
        auditLogger.recordEvent("Deleted Delivery #" + did);
        return assessment;
    }

    public Delivery updateDelivery(final long did, final DeliveryTemplate template)
            throws BindException, PrivilegeException, DomainEntityNotFoundException {
        /* Validate template */
        validateDeliveryTemplate(template);

        /* Look up delivery and check privileges */
        final Delivery delivery = lookupDelivery(did);
        final Assessment assessment = delivery.getAssessment();
        ensureCallerMayManage(assessment);

        /* Look up settings and check privileges */
        final Long dsid = template.getDsid();
        final DeliverySettings deliverySettings = (dsid!=null) ? lookupAndMatchDeliverySettings(dsid.longValue(), assessment) : null;

        /* Update data */
        delivery.setOpen(template.isOpen());
        delivery.setTitle(template.getTitle().trim());
        delivery.setLtiEnabled(template.isLtiEnabled());
        delivery.setDeliverySettings(deliverySettings);
        deliveryDao.update(delivery);
        return delivery;
    }

    private void validateDeliveryTemplate(final DeliveryTemplate template)
            throws BindException {
        Assert.notNull(template, "deliveryTemplate");
        final BeanPropertyBindingResult errors = new BeanPropertyBindingResult(template, "deliveryTemplate");
        jsr303Validator.validate(template, errors);
        if (errors.hasErrors()) {
            throw new BindException(errors);
        }
    }

    //-------------------------------------------------
    // Assessment trying

    public Delivery createDemoDelivery(final Assessment assessment, final DeliverySettings deliverySettings)
            throws PrivilegeException {
        Assert.notNull(assessment, "assessment");
        if (deliverySettings!=null) {
            ensureCompatible(deliverySettings, assessment);
        }

        /* Check access rights */
        ensureCallerMayManage(assessment);

        /* Create demo Delivery */
        final Delivery delivery = new Delivery();
        delivery.setAssessment(assessment);
        delivery.setDeliverySettings(deliverySettings);
        delivery.setDeliveryType(DeliveryType.USER_TRANSIENT);
        delivery.setOpen(true);
        delivery.setTitle("Temporary demo delivery");
        deliveryDao.persist(delivery);

        /* That's it! */
        auditLogger.recordEvent("Created demo Delivery #" + delivery.getId() + " for Assessment #" + assessment.getId());
        return delivery;
    }

    //-------------------------------------------------
    // Internal helpers

    private int terminateCandidateSessions(final Assessment assessment) {
        final List<CandidateSession> nonTerminatedCandidateSessions = candidateSessionDao.getNonTerminatedForAssessment(assessment);
        for (final CandidateSession candidateSession : nonTerminatedCandidateSessions) {
            candidateSession.setTerminated(true);
            candidateSessionDao.update(candidateSession);
        }
        return nonTerminatedCandidateSessions.size();
    }

    private void ensureCompatible(final DeliverySettings deliverySettings, final Assessment assessment)
            throws PrivilegeException {
        ensureCompatible(deliverySettings, assessment.getAssessmentType());
    }

    private void ensureCompatible(final DeliverySettings deliverySettings, final AssessmentObjectType assessmentObjectType)
            throws PrivilegeException {
        final User caller = identityService.getCurrentThreadUser();
        if (assessmentObjectType!=deliverySettings.getAssessmentType()) {
            throw new PrivilegeException(caller, Privilege.MATCH_DELIVERY_SETTINGS, deliverySettings);
        }
    }

    private void deleteAssessmentPackageSandbox(final AssessmentPackage assessmentPackage) {
        final String sandboxPath = assessmentPackage.getSandboxPath();
        if (sandboxPath==null) {
            throw new QtiWorksLogicException("AssessmentPackage sandbox is null");
        }
        filespaceManager.deleteSandbox(new File(sandboxPath));
        assessmentPackage.setSandboxPath(null);
    }

    private AssessmentPackage importPackageFiles(final MultipartFile multipartFile)
            throws AssessmentPackageFileImportException {
        final User owner = identityService.getCurrentThreadUser();
        final File packageSandbox = filespaceManager.createAssessmentPackageSandbox(owner);
        try {
            final AssessmentPackage assessmentPackage = assessmentPackageFileImporter.importAssessmentPackageData(packageSandbox, multipartFile);
            assessmentPackage.setImporter(owner);
            return assessmentPackage;
        }
        catch (final AssessmentPackageFileImportException e) {
            filespaceManager.deleteSandbox(packageSandbox);
            throw e;
        }
    }
}
