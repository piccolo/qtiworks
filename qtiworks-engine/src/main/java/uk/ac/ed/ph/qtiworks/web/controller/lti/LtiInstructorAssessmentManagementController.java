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
package uk.ac.ed.ph.qtiworks.web.controller.lti;

import uk.ac.ed.ph.qtiworks.QtiWorksLogicException;
import uk.ac.ed.ph.qtiworks.QtiWorksRuntimeException;
import uk.ac.ed.ph.qtiworks.domain.DomainEntityNotFoundException;
import uk.ac.ed.ph.qtiworks.domain.PrivilegeException;
import uk.ac.ed.ph.qtiworks.domain.entities.Assessment;
import uk.ac.ed.ph.qtiworks.domain.entities.CandidateSession;
import uk.ac.ed.ph.qtiworks.domain.entities.Delivery;
import uk.ac.ed.ph.qtiworks.domain.entities.DeliverySettings;
import uk.ac.ed.ph.qtiworks.services.AssessmentDataService;
import uk.ac.ed.ph.qtiworks.services.AssessmentManagementService;
import uk.ac.ed.ph.qtiworks.services.CandidateSessionStarter;
import uk.ac.ed.ph.qtiworks.services.base.IdentityService;
import uk.ac.ed.ph.qtiworks.services.dao.CandidateSessionDao;
import uk.ac.ed.ph.qtiworks.services.domain.AssessmentAndPackage;
import uk.ac.ed.ph.qtiworks.services.domain.AssessmentPackageFileImportException;
import uk.ac.ed.ph.qtiworks.services.domain.AssessmentPackageFileImportException.APFIFailureReason;
import uk.ac.ed.ph.qtiworks.services.domain.AssessmentStateException;
import uk.ac.ed.ph.qtiworks.services.domain.AssessmentStateException.APSFailureReason;
import uk.ac.ed.ph.qtiworks.services.domain.EnumerableClientFailure;
import uk.ac.ed.ph.qtiworks.services.domain.UpdateAssessmentCommand;
import uk.ac.ed.ph.qtiworks.web.GlobalRouter;
import uk.ac.ed.ph.qtiworks.web.domain.UploadAssessmentPackageCommand;

import uk.ac.ed.ph.jqtiplus.validation.AssessmentObjectValidationResult;

import java.util.List;

import javax.annotation.Resource;
import javax.validation.Valid;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Controller for instructor assessment management when running over LTI (domain-level launch)
 *
 * @author David McKain
 */
@Controller
@RequestMapping("/resource/{lrid}")
public class LtiInstructorAssessmentManagementController {

    @Resource
    private LtiInstructorRouter ltiInstructorRouter;

    @Resource
    private IdentityService identityService;

    @Resource
    private AssessmentDataService assessmentDataService;

    @Resource
    private AssessmentManagementService assessmentManagementService;

    @Resource
    private CandidateSessionStarter candidateSessionStarter;

    @Resource
    private CandidateSessionDao candidateSessionDao;

    //------------------------------------------------------

    @ModelAttribute
    public void setupModel(final Model model) {
        model.addAttribute("ltiUser", identityService.getCurrentThreadUser());
        model.addAttribute("ltiResource", identityService.ensureCurrentThreadLtiResource());
        model.addAttribute("primaryRouting", ltiInstructorRouter.buildPrimaryRouting());
    }

    //------------------------------------------------------

    @RequestMapping(value="", method=RequestMethod.GET)
    public String resourceTopPage() {
        return "resource";
    }

    /** Lists all Assignments in this LTI context */
    @RequestMapping(value="/assessments", method=RequestMethod.GET)
    public String listContextAssessments(final Model model) {
        final List<AssessmentAndPackage> assessments = assessmentDataService.getCallerLtiContextAssessments();
        model.addAttribute(assessments);
        model.addAttribute("assessmentRouting", ltiInstructorRouter.buildAssessmentListRouting(assessments));
        return "listAssessments";
    }

    //------------------------------------------------------

    @RequestMapping(value="/assessments/upload", method=RequestMethod.GET)
    public String showUploadAssessmentForm(final Model model) {
        model.addAttribute(new UploadAssessmentPackageCommand());
        return "uploadAssessmentForm";
    }

    @RequestMapping(value="/assessments/upload", method=RequestMethod.POST)
    public String handleUploadAssessmentForm(final RedirectAttributes redirectAttributes,
            final @Valid @ModelAttribute UploadAssessmentPackageCommand command,
            final BindingResult result)
            throws PrivilegeException {
        /* Validate command Object */
        if (result.hasErrors()) {
            return "uploadAssessmentForm";
        }

        /* Attempt to import the package */
        Assessment assessment;
        try {
            assessment = assessmentManagementService.importAssessment(command.getFile());
        }
        catch (final AssessmentPackageFileImportException e) {
            final EnumerableClientFailure<APFIFailureReason> failure = e.getFailure();
            failure.registerErrors(result, "assessmentPackageUpload");
            return "uploadAssessmentForm";
        }
        try {
            assessmentManagementService.validateAssessment(assessment.getId().longValue());
        }
        catch (final DomainEntityNotFoundException e) {
            /* This could only happen if there's some kind of race condition */
            throw QtiWorksRuntimeException.unexpectedException(e);
        }
        GlobalRouter.addFlashMessage(redirectAttributes, "Assessment successfully created");
        return ltiInstructorRouter.buildInstructorRedirect("/assessment/" + assessment.getId());
    }

    //------------------------------------------------------

    /** Shows the Assessment having the given ID (aid) */
    @RequestMapping(value="/assessment/{aid}", method=RequestMethod.GET)
    public String showAssessment(@PathVariable final long aid, final Model model)
            throws PrivilegeException, DomainEntityNotFoundException {
        final Assessment assessment = assessmentManagementService.lookupAssessment(aid);
        setupModelForAssessment(assessment, model);
        return "showAssessment";
    }

    private void setupModelForAssessment(final long aid, final Model model)
            throws PrivilegeException, DomainEntityNotFoundException {
        setupModelForAssessment(assessmentManagementService.lookupAssessment(aid), model);
    }

    private void setupModelForAssessment(final Assessment assessment, final Model model) {
        model.addAttribute("assessment", assessment);
        model.addAttribute("assessmentRouting", ltiInstructorRouter.buildAssessmentRouting(assessment));
        model.addAttribute("assessmentPackage", assessmentDataService.ensureSelectedAssessmentPackage(assessment));
        model.addAttribute("deliverySettingsList", assessmentDataService.getCallerLtiContextDeliverySettingsForType(assessment.getAssessmentType()));
        model.addAttribute("assessmentRunningSessionCount", candidateSessionDao.countRunningForAssessment(assessment));
    }

    @RequestMapping(value="/assessment/{aid}/edit", method=RequestMethod.GET)
    public String showEditAssessmentForm(@PathVariable final long aid, final Model model)
            throws PrivilegeException, DomainEntityNotFoundException {
        final Assessment assessment = assessmentManagementService.lookupAssessment(aid);

        final UpdateAssessmentCommand command = new UpdateAssessmentCommand();
        command.setName(assessment.getName());
        command.setTitle(assessment.getTitle());
        model.addAttribute(command);

        setupModelForAssessment(assessment, model);
        return "editAssessmentForm";
    }

    @RequestMapping(value="/assessment/{aid}/edit", method=RequestMethod.POST)
    public String handleEditAssessmentForm(@PathVariable final long aid, final Model model,
            final RedirectAttributes redirectAttributes,
            final @Valid @ModelAttribute UpdateAssessmentCommand command, final BindingResult result)
            throws PrivilegeException, DomainEntityNotFoundException {
        /* Validate command Object */
        if (result.hasErrors()) {
            setupModelForAssessment(aid, model);
            return "editAssessmentForm";
        }
        try {
            assessmentManagementService.updateAssessment(aid, command);
        }
        catch (final BindException e) {
            throw new QtiWorksLogicException("Top layer validation is currently same as service layer in this case, so this Exception should not happen");
        }
        GlobalRouter.addFlashMessage(redirectAttributes, "Assessment successfully edited");
        return ltiInstructorRouter.buildInstructorRedirect("/assessment/" + aid);
    }

    @RequestMapping(value="/assessment/{aid}/upload", method=RequestMethod.GET)
    public String showUpdateAssessmentPackageForm(final @PathVariable long aid,
            final Model model)
            throws PrivilegeException, DomainEntityNotFoundException {
        model.addAttribute(new UploadAssessmentPackageCommand());
        setupModelForAssessment(aid, model);
        return "updateAssessmentPackageForm";
    }

    @RequestMapping(value="/assessment/{aid}/upload", method=RequestMethod.POST)
    public String handleUploadAssessmentPackageForm(final @PathVariable long aid,
            final Model model, final RedirectAttributes redirectAttributes,
            final @Valid @ModelAttribute UploadAssessmentPackageCommand command, final BindingResult result)
            throws PrivilegeException, DomainEntityNotFoundException {
        /* Make sure something was submitted */
        /* Validate command Object */
        if (result.hasErrors()) {
            setupModelForAssessment(aid, model);
            return "updateAssessmentPackageForm";
        }

        /* Attempt to import the package */
        final MultipartFile uploadFile = command.getFile();
        try {
            assessmentManagementService.replaceAssessmentPackage(aid, uploadFile);
        }
        catch (final AssessmentPackageFileImportException e) {
            final EnumerableClientFailure<APFIFailureReason> failure = e.getFailure();
            failure.registerErrors(result, "assessmentPackageUpload");
            setupModelForAssessment(aid, model);
            return "updateAssessmentPackageForm";
        }
        catch (final AssessmentStateException e) {
            final EnumerableClientFailure<APSFailureReason> failure = e.getFailure();
            failure.registerErrors(result, "assessmentPackageUpload");
            setupModelForAssessment(aid, model);
            return "updateAssessmentPackageForm";
        }
        try {
            assessmentManagementService.validateAssessment(aid);
        }
        catch (final DomainEntityNotFoundException e) {
            /* This could only happen if there's some kind of race condition */
            throw QtiWorksRuntimeException.unexpectedException(e);
        }
        GlobalRouter.addFlashMessage(redirectAttributes, "Assessment package content successfully replaced");
        return ltiInstructorRouter.buildInstructorRedirect("/assessment/{aid}");
    }

    @RequestMapping(value="/assessment/{aid}/validate", method=RequestMethod.GET)
    public String validateAssessment(final @PathVariable long aid, final Model model)
            throws PrivilegeException, DomainEntityNotFoundException {
        final AssessmentObjectValidationResult<?> validationResult = assessmentManagementService.validateAssessment(aid);
        model.addAttribute("validationResult", validationResult);
        setupModelForAssessment(aid, model);
        return "validationResult";
    }

    @RequestMapping(value="/assessment/{aid}/delete", method=RequestMethod.POST)
    public String deleteAssessment(final @PathVariable long aid, final RedirectAttributes redirectAttributes)
            throws PrivilegeException, DomainEntityNotFoundException {
        assessmentManagementService.deleteAssessment(aid);
        GlobalRouter.addFlashMessage(redirectAttributes, "Assessment successfully deleted");
        return ltiInstructorRouter.buildInstructorRedirect("/assessments");
    }

    @RequestMapping(value="/assessment/{aid}/try", method=RequestMethod.POST)
    public String tryAssessment(final @PathVariable long aid)
            throws PrivilegeException, DomainEntityNotFoundException {
        final Assessment assessment = assessmentManagementService.lookupAssessment(aid);
        final Delivery demoDelivery = assessmentManagementService.createDemoDelivery(assessment, null);
        return runDelivery(aid, demoDelivery, true);
    }

    @RequestMapping(value="/assessment/{aid}/try/{dsid}", method=RequestMethod.POST)
    public String tryAssessment(final @PathVariable long aid, final @PathVariable long dsid)
            throws PrivilegeException, DomainEntityNotFoundException {
        final Assessment assessment = assessmentManagementService.lookupAssessment(aid);
        final DeliverySettings deliverySettings = assessmentManagementService.lookupAndMatchDeliverySettings(dsid, assessment);
        final Delivery demoDelivery = assessmentManagementService.createDemoDelivery(assessment, deliverySettings);
        return runDelivery(aid, demoDelivery, true);
    }

    private String runDelivery(final long aid, final Delivery delivery, final boolean authorMode)
            throws PrivilegeException {
        final String exitUrl = ltiInstructorRouter.buildWithinContextUrl("/assessment/" + aid);
        final CandidateSession candidateSession = candidateSessionStarter.createCandidateSession(delivery, authorMode, exitUrl, null, null);
        return GlobalRouter.buildSessionStartRedirect(candidateSession);
    }
}
