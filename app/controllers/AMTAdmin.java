package controllers;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.mturk.AmazonMTurk;
import com.amazonaws.services.mturk.AmazonMTurkClientBuilder;
import com.amazonaws.services.mturk.model.*;
import com.amazonaws.services.mturk.model.QualificationRequirement;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.*;
import org.apache.commons.io.IOUtils;
import play.Play;
import play.data.Form;
import play.libs.*;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Security;
import views.html.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class AMTAdmin extends Controller {
  private static final String PRODUCTION_ENDPOINT = "mturk-requester.us-east-1.amazonaws.com";
  private static final String SANDBOX_ENDPOINT = "mturk-requester-sandbox.us-east-1.amazonaws.com";
  private static final String SIGNING_REGION = "us-east-1";
  private static final String SECRET_KEY = Play.application().configuration().getString("amt.secretKey");
  private static final String ACCESS_KEY = Play.application().configuration().getString("amt.accessKey");

  @Security.Authenticated(Secured.class)
  public static Result getAccountBalance(Boolean sandbox) {
    try {
      AWSStaticCredentialsProvider awsCredentials = new AWSStaticCredentialsProvider(new BasicAWSCredentials(ACCESS_KEY, SECRET_KEY));
      AmazonMTurkClientBuilder builder = AmazonMTurkClientBuilder.standard().withCredentials(awsCredentials);
      builder.setEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration((sandbox ? SANDBOX_ENDPOINT : PRODUCTION_ENDPOINT), SIGNING_REGION));
      AmazonMTurk mTurk = builder.build();
      GetAccountBalanceRequest getAccountBalanceRequest= new GetAccountBalanceRequest();
      GetAccountBalanceResult getAccountBalanceResult = mTurk.getAccountBalance(getAccountBalanceRequest);
      String availableBalance = getAccountBalanceResult.getAvailableBalance();
      String onHoldBalance = getAccountBalanceResult.getOnHoldBalance();
      ObjectNode returnJson = Json.newObject();
      returnJson.put("availableBalance", availableBalance);
      returnJson.put("onHoldBalance", onHoldBalance);
      return ok(returnJson);
    } catch (AmazonServiceException ase) {
      return badRequest(ase.getMessage());
    } catch (AmazonClientException ace) {
      return internalServerError(ace.getMessage());
    }
  }

  @Security.Authenticated(Secured.class)
  public static Result listHITs(String nextToken, Integer maxResults, Boolean sandbox) {
    try {
      AWSStaticCredentialsProvider awsCredentials = new AWSStaticCredentialsProvider(new BasicAWSCredentials(ACCESS_KEY, SECRET_KEY));
      AmazonMTurkClientBuilder builder = AmazonMTurkClientBuilder.standard().withCredentials(awsCredentials);
      builder.setEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration((sandbox ? SANDBOX_ENDPOINT : PRODUCTION_ENDPOINT), SIGNING_REGION));
      AmazonMTurk mTurk = builder.build();
      ListHITsRequest listHITsRequest = new ListHITsRequest().withMaxResults((maxResults == null) ? 20 : maxResults);
      if (nextToken != null) listHITsRequest.setNextToken(nextToken);
      ListHITsResult hitResults = mTurk.listHITs(listHITsRequest);
      List<HIT> hits = hitResults.getHITs();

      // Update amt_hits table
      for (HIT hit : hits) {
        AMTHit amtHit = AMTHit.findByHitId(hit.getHITId());
        if (amtHit == null) {
          amtHit = new AMTHit();
        }
        amtHit.hitId = hit.getHITId();
        amtHit.creationDate = hit.getCreationTime();
        amtHit.title = hit.getTitle();
        amtHit.description = hit.getDescription();
        amtHit.maxAssignments = hit.getMaxAssignments().toString();
        amtHit.reward = hit.getReward();
        amtHit.sandbox = sandbox;
        amtHit.save();
      }

      String next = hitResults.getNextToken();
      ObjectNode returnJson = Json.newObject();
      returnJson.put("hits", Json.toJson(hits));
      returnJson.put("nextToken", next);
      return ok(returnJson);
    } catch (AmazonServiceException ase) {
      return badRequest(ase.getMessage());
    } catch (AmazonClientException ace) {
      return internalServerError(ace.getMessage());
    }
  }

  @Security.Authenticated(Secured.class)
  public static Result listAssignmentsForHIT(String hitId, Integer maxResults, String nextToken, Boolean sandbox) {
    try {
      AWSStaticCredentialsProvider awsCredentials = new AWSStaticCredentialsProvider(new BasicAWSCredentials(ACCESS_KEY, SECRET_KEY));
      AmazonMTurkClientBuilder builder = AmazonMTurkClientBuilder.standard().withCredentials(awsCredentials);
      builder.setEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration((sandbox ? SANDBOX_ENDPOINT : PRODUCTION_ENDPOINT), SIGNING_REGION));
      AmazonMTurk mTurk = builder.build();
      ListAssignmentsForHITRequest listAssignmentsForHITRequest = new ListAssignmentsForHITRequest().withMaxResults(maxResults).withHITId(hitId);
      ObjectNode returnJson = Json.newObject();
      ArrayNode jsonAssignments = returnJson.putArray("assignments");
      if (nextToken != null) listAssignmentsForHITRequest.setNextToken(nextToken);
      ListAssignmentsForHITResult assignmentResults = mTurk.listAssignmentsForHIT(listAssignmentsForHITRequest);
      List<Assignment> assignments = assignmentResults.getAssignments();

      // Update amt_assignments table
      for (Assignment assignment : assignments) {
        AMTHit hit = AMTHit.findByHitId(assignment.getHITId());
        AMTAssignment amtAssignment = hit.getAMTAssignmentById(assignment.getAssignmentId());
        boolean update = true;
        if (amtAssignment == null) {
          amtAssignment = new AMTAssignment();
          // Default to completed, require unchecking completed box to permit repeat play
          amtAssignment.assignmentCompleted = true;
          update = false;
        }
        amtAssignment.amtHit = hit;
        amtAssignment.assignmentId = assignment.getAssignmentId();
        amtAssignment.workerId = assignment.getWorkerId();
        amtAssignment.assignmentStatus = assignment.getAssignmentStatus();
        amtAssignment.autoApprovalTime = assignment.getAutoApprovalTime().toString();
        amtAssignment.acceptTime = (assignment.getAcceptTime() == null) ? null :  assignment.getAcceptTime().toString();
        amtAssignment.submitTime = (assignment.getSubmitTime() == null) ? null :  assignment.getSubmitTime().toString();
        amtAssignment.answer = assignment.getAnswer();

        if (!update) {
          hit.amtAssignments.add(amtAssignment);
        }
        hit.save();

        ObjectNode jsonAssignment = Json.newObject();
        jsonAssignment.put("assignmentId", amtAssignment.assignmentId);
        jsonAssignment.put("workerId", amtAssignment.workerId);
        jsonAssignment.put("approvalTime", (assignment.getApprovalTime() == null) ? null : assignment.getApprovalTime().toString());
        jsonAssignment.put("rejectionTime", (assignment.getRejectionTime() == null) ? null : assignment.getRejectionTime().toString());
        jsonAssignment.put("answer", amtAssignment.answer);
        jsonAssignment.put("assignmentCompleted", amtAssignment.assignmentCompleted);

        jsonAssignments.add(jsonAssignment);
      }

      String token = assignmentResults.getNextToken();
      returnJson.put("nextToken", token);
      return ok(returnJson);
    } catch (AmazonServiceException ase) {
      return badRequest(ase.getMessage());
    } catch (AmazonClientException ace) {
      return internalServerError(ace.getMessage());
    }
  }

  @Security.Authenticated(Secured.class)
  public static Result listBonusPaymentsForHIT(String hitId, Integer maxResults, String nextToken, Boolean sandbox) {
    try {
      AWSStaticCredentialsProvider awsCredentials = new AWSStaticCredentialsProvider(new BasicAWSCredentials(ACCESS_KEY, SECRET_KEY));
      AmazonMTurkClientBuilder builder = AmazonMTurkClientBuilder.standard().withCredentials(awsCredentials);
      builder.setEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration((sandbox ? SANDBOX_ENDPOINT : PRODUCTION_ENDPOINT), SIGNING_REGION));
      AmazonMTurk mTurk = builder.build();
      ListBonusPaymentsRequest listBonusPaymentsRequest = new ListBonusPaymentsRequest().withMaxResults(maxResults).withHITId(hitId);
      if (nextToken != null) listBonusPaymentsRequest.setNextToken(nextToken);
      ListBonusPaymentsResult bonusPaymentsResults = mTurk.listBonusPayments(listBonusPaymentsRequest);
      List<BonusPayment> bonusPayments = bonusPaymentsResults.getBonusPayments();
      // Update amt_assignments table
      for (BonusPayment bonusPayment : bonusPayments) {
        AMTAssignment amtAssignment = AMTAssignment.findByAssignmentId(bonusPayment.getAssignmentId());
        if (amtAssignment != null) {
          amtAssignment.bonusGranted = true;
          amtAssignment.bonusAmount = bonusPayment.getBonusAmount();
          amtAssignment.save();
        }
      }
      String token = bonusPaymentsResults.getNextToken();
      ObjectNode returnJson = Json.newObject();
      returnJson.put("bonusPayments", Json.toJson(bonusPayments));
      returnJson.put("nextToken", token);
      return ok(returnJson);
    } catch (AmazonServiceException ase) {
      return badRequest(ase.getMessage());
    } catch (AmazonClientException ace) {
      return internalServerError(ace.getMessage());
    }
  }

  @Security.Authenticated(Secured.class)
  public static Result approveAssignment(String assignmentId, Boolean sandbox) {
    try {
      AWSStaticCredentialsProvider awsCredentials = new AWSStaticCredentialsProvider(new BasicAWSCredentials(ACCESS_KEY, SECRET_KEY));
      AmazonMTurkClientBuilder builder = AmazonMTurkClientBuilder.standard().withCredentials(awsCredentials);
      builder.setEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration((sandbox ? SANDBOX_ENDPOINT : PRODUCTION_ENDPOINT), SIGNING_REGION));
      AmazonMTurk mTurk = builder.build();
      ApproveAssignmentRequest approveAssignmentRequest= new ApproveAssignmentRequest().withAssignmentId(assignmentId);
      mTurk.approveAssignment(approveAssignmentRequest);
      return ok();
    } catch (AmazonServiceException ase) {
      return badRequest(ase.getMessage());
    } catch (AmazonClientException ace) {
      return internalServerError(ace.getMessage());
    }
  }

  @Security.Authenticated(Secured.class)
  public static Result rejectAssignment(String assignmentId, Boolean sandbox) {
    String requesterFeedback = null;
    JsonNode json = request().body().asJson();
    if(json == null) {
      return badRequest("Expecting Json data");
    } else {
      requesterFeedback = json.findPath("requesterFeedback").textValue();
    }

    if (requesterFeedback == null) {
      return badRequest("Please provide requester feedback.");
    }
    try {
      AWSStaticCredentialsProvider awsCredentials = new AWSStaticCredentialsProvider(new BasicAWSCredentials(ACCESS_KEY, SECRET_KEY));
      AmazonMTurkClientBuilder builder = AmazonMTurkClientBuilder.standard().withCredentials(awsCredentials);
      builder.setEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration((sandbox ? SANDBOX_ENDPOINT : PRODUCTION_ENDPOINT), SIGNING_REGION));
      AmazonMTurk mTurk = builder.build();
      RejectAssignmentRequest rejectAssignmentRequest= new RejectAssignmentRequest()
          .withAssignmentId(assignmentId)
          .withRequesterFeedback(requesterFeedback);
      mTurk.rejectAssignment(rejectAssignmentRequest);
      return ok();
    } catch (AmazonServiceException ase) {
      return badRequest(ase.getMessage());
    } catch (AmazonClientException ace) {
      return internalServerError(ace.getMessage());
    }
  }

  @Security.Authenticated(Secured.class)
  public static Result sendBonus(String assignmentId, Boolean sandbox) {
    String bonusAmount = null;
    String reason = null;
    String workerId = null;
    JsonNode json = request().body().asJson();
    if(json == null) {
      return badRequest("Expecting Json data");
    } else {
      bonusAmount = json.findPath("bonusAmount").textValue();
      reason = json.findPath("reason").textValue();
      workerId = json.findPath("workerId").textValue();
    }

    if (bonusAmount == null || reason == null || workerId == null) {
      return badRequest("Please provide workerId, bonusAmount, and reason.");
    }
    try {
      AWSStaticCredentialsProvider awsCredentials = new AWSStaticCredentialsProvider(new BasicAWSCredentials(ACCESS_KEY, SECRET_KEY));
      AmazonMTurkClientBuilder builder = AmazonMTurkClientBuilder.standard().withCredentials(awsCredentials);
      builder.setEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration((sandbox ? SANDBOX_ENDPOINT : PRODUCTION_ENDPOINT), SIGNING_REGION));
      AmazonMTurk mTurk = builder.build();
      SendBonusRequest sendBonusRequest= new SendBonusRequest().withAssignmentId(assignmentId).withBonusAmount(bonusAmount).withReason(reason).withWorkerId(workerId);
      mTurk.sendBonus(sendBonusRequest);
      return ok();
    } catch (AmazonServiceException ase) {
      return badRequest(ase.getMessage());
    } catch (AmazonClientException ace) {
      return internalServerError(ace.getMessage());
    }
  }

  @Security.Authenticated(Secured.class)
  public static Result updateAssignmentCompleted(String assignmentId) {
    String completedText;

    JsonNode json = request().body().asJson();

    if(json == null) {
      return badRequest("Expecting Json data");
    } else {
      completedText = json.findPath("completed").asText();
    }

    if (completedText == null || (!(completedText.matches("(?i)0|1|true|false")))) {
      return badRequest("Please provide completed as a boolean value (0, 1, true, false).");
    }

    AMTAssignment amtAssignment = AMTAssignment.findByAssignmentId(assignmentId);

    if (amtAssignment == null) {
      return badRequest("Invalid Assignment ID.");
    }

    Boolean completed = (completedText.matches("(?i)1|true"));

    amtAssignment.assignmentCompleted = completed;
    amtAssignment.save();

    return ok();
  }

  @Security.Authenticated(Secured.class)
  public static Result createHIT(Boolean sandbox) {
    String title;
    String description;
    String reward;
    Integer maxAssignments;
    Long hitLifetime;
    Long tutorialTime;
    Long assignmentDuration;
    String keywords;
    String disallowPrevious;
    String experimentId;
    String experimentInstanceId;

    JsonNode json = request().body().asJson();
    if (json == null) {
      return badRequest("Expecting Json data");
    } else {
      title = json.findPath("title").textValue();
      description = json.findPath("description").textValue();
      reward = json.findPath("reward").textValue();
      maxAssignments = json.findPath("maxAssignments").asInt(-1);
      hitLifetime = json.findPath("hitLifetime").asLong(-1L);
      tutorialTime = json.findPath("tutorialTime").asLong(-1L);
      assignmentDuration = json.findPath("assignmentDuration").asLong(-1L);
      keywords = json.findPath("keywords").textValue();
      disallowPrevious = json.findPath("disallowPrevious").textValue();
      experimentId = json.findPath("experimentId").textValue();
      experimentInstanceId = json.findPath("experimentInstanceId").textValue();
    }

    if (title == null || description == null || reward == null || maxAssignments < 0 || hitLifetime < 0 || tutorialTime < 0 || assignmentDuration < 0 || keywords == null || disallowPrevious == null || experimentId == null || experimentInstanceId == null) {
      return badRequest("Please provide experiment ID, experiment instance ID, title, description, reward, max assignments, hit lifetime, tutorial time, assignment duration, keywords, and allow repeat play option.");
    }

    String rootURL = play.Play.application().configuration().getString("breadboard.rootUrl");
    String gameURL = String.format("/game/%1$s/%2$s/amt", experimentId, experimentInstanceId);
    String externalURL = rootURL + gameURL;
    Integer frameHeight = play.Play.application().configuration().getInt("breadboard.amtFrameHeight");
    String question = "<ExternalQuestion xmlns=\"http://mechanicalturk.amazonaws.com/AWSMechanicalTurkDataSchemas/2006-07-14/ExternalQuestion.xsd\">\n" +
        "  <ExternalURL>" + externalURL + "</ExternalURL>\n" +
        "  <FrameHeight>" + frameHeight + "</FrameHeight>\n" +
        "</ExternalQuestion>";

    try {
      AWSStaticCredentialsProvider awsCredentials = new AWSStaticCredentialsProvider(new BasicAWSCredentials(ACCESS_KEY, SECRET_KEY));
      AmazonMTurkClientBuilder builder = AmazonMTurkClientBuilder.standard().withCredentials(awsCredentials);
      builder.setEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration((sandbox ? SANDBOX_ENDPOINT : PRODUCTION_ENDPOINT), SIGNING_REGION));
      AmazonMTurk mTurk = builder.build();

      // Create HIT
      CreateHITRequest createHITRequest = new CreateHITRequest()
          .withQuestion(question)
          .withTitle(title)
          .withDescription(description)
          .withMaxAssignments(maxAssignments)
          .withLifetimeInSeconds(hitLifetime)
          .withAssignmentDurationInSeconds(assignmentDuration)
          .withKeywords(keywords)
          .withReward(reward);

      CreateHITResult createHITResult = mTurk.createHIT(createHITRequest);
      HIT hit = createHITResult.getHIT();
      AMTHit amtHit = new AMTHit();
      amtHit.hitId = hit.getHITId();
      amtHit.description = hit.getDescription();
      amtHit.lifetimeInSeconds = hitLifetime.toString();
      amtHit.tutorialTime = tutorialTime.toString();
      amtHit.maxAssignments = hit.getMaxAssignments().toString();
      amtHit.externalURL = externalURL;
      amtHit.reward = hit.getReward();
      amtHit.title = hit.getTitle();
      amtHit.disallowPrevious = disallowPrevious;
      amtHit.sandbox = sandbox;

      ExperimentInstance experimentInstance = ExperimentInstance.findById(Long.parseLong(experimentInstanceId));
      experimentInstance.amtHits.add(amtHit);
      experimentInstance.save();

      return ok();
    } catch (AmazonServiceException ase) {
      return badRequest(ase.getMessage());
    } catch (AmazonClientException ace) {
      return internalServerError(ace.getMessage());
    }
  }

  @Security.Authenticated(Secured.class)
  public static Result createDummyHit(Boolean sandbox) {
    String workerId = null;
    String reason = null;
    String reward = null;
    String paymentHitHtml = getDummyHitHTML();
    if (paymentHitHtml == null) {
      return badRequest("Unable to read 'payment-hit.html' or 'default-payment-hit.html' file in conf/defaults directory.");
    }
    JsonNode json = request().body().asJson();
    if(json == null) {
      return badRequest("Expecting Json data");
    } else {
      workerId = json.findPath("workerId").textValue();
      reason = json.findPath("reason").textValue();
      reward = json.findPath("reward").textValue();
    }

    if (workerId == null || reason == null || reward == null) {
      return badRequest("Please provide workerId, reason, and reward.");
    }

    try {
      AWSStaticCredentialsProvider awsCredentials = new AWSStaticCredentialsProvider(new BasicAWSCredentials(ACCESS_KEY, SECRET_KEY));
      AmazonMTurkClientBuilder builder = AmazonMTurkClientBuilder.standard().withCredentials(awsCredentials);
      builder.setEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration((sandbox ? SANDBOX_ENDPOINT : PRODUCTION_ENDPOINT), SIGNING_REGION));
      AmazonMTurk mTurk = builder.build();

      // Create a qualification for the worker
      String qualificationName = new Date().getTime() + "_" + workerId;
      CreateQualificationTypeRequest createQualificationTypeRequest = new CreateQualificationTypeRequest()
          .withName(qualificationName)
          .withDescription(reason)
          .withAutoGranted(true)
          .withAutoGrantedValue(1)
          .withQualificationTypeStatus(QualificationTypeStatus.Active);
      CreateQualificationTypeResult createQualificationTypeResult = mTurk.createQualificationType(createQualificationTypeRequest);

      AssociateQualificationWithWorkerRequest associateQualificationWithWorkerRequest = new AssociateQualificationWithWorkerRequest()
          .withQualificationTypeId(createQualificationTypeResult.getQualificationType().getQualificationTypeId())
          .withWorkerId(workerId);

      mTurk.associateQualificationWithWorker(associateQualificationWithWorkerRequest);

      QualificationRequirement qualificationRequirement = new QualificationRequirement()
          .withQualificationTypeId(createQualificationTypeResult.getQualificationType().getQualificationTypeId())
          .withRequiredToPreview(true)
          .withComparator("EqualTo")
          .withIntegerValues(1);

      // Create Dummy/Payment HIT
      CreateHITRequest createHITRequest = new CreateHITRequest()
          .withTitle("HIT for " + workerId)
          .withDescription(reason)
          .withMaxAssignments(1)
          .withQuestion(paymentHitHtml)
          .withQualificationRequirements(qualificationRequirement)
          .withLifetimeInSeconds(31536000L)
          .withAssignmentDurationInSeconds(5400L)
          .withKeywords(workerId)
          .withReward(reward);

      mTurk.createHIT(createHITRequest);

      return ok();
    } catch (AmazonServiceException ase) {
      return badRequest(ase.getMessage());
    } catch (AmazonClientException ace) {
      return internalServerError(ace.getMessage());
    }
  }

  private static String getDummyHitHTML() {
    String returnString = null;
    InputStream paymentHitHtml = Play.application().resourceAsStream("defaults/payment-hit.html");
    if (paymentHitHtml == null) paymentHitHtml = Play.application().resourceAsStream("defaults/default-payment-hit.html");
    if (paymentHitHtml == null) return null;
    try {
      returnString = "<HTMLQuestion xmlns=\"http://mechanicalturk.amazonaws.com/AWSMechanicalTurkDataSchemas/2011-11-11/HTMLQuestion.xsd\">\n" +
                     "  <HTMLContent><![CDATA[" +
                     IOUtils.toString(paymentHitHtml) +
                     "]]>\n" +
                     "  </HTMLContent>\n" +
                     "  <FrameHeight>600</FrameHeight>\n" +
                     "</HTMLQuestion>";
    } catch (IOException ioe) { }
    return returnString;
  }

}
