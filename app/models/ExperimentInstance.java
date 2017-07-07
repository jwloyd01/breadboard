package models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import play.Logger;
import play.libs.Json;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.*;
import javax.persistence.*;
import java.text.SimpleDateFormat;

import play.db.ebean.*;
import play.data.format.*;
import play.data.validation.*;

import com.avaje.ebean.*;

@Entity
@Table(name = "experiment_instances")
public class ExperimentInstance extends Model {
    @Version
    public int version;

    @Id
    public Long id;

    @Constraints.Required
    @Formats.NonEmpty
    public Date creationDate;

    @Constraints.Required
    @Formats.NonEmpty
    public String name;

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "experimentInstance", orphanRemoval = true)
    public List<Data> data = new ArrayList<Data>();

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "experimentInstance")
    public List<Event> events = new ArrayList<Event>();

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "experimentInstance")
    public List<AMTHit> amtHits = new ArrayList<AMTHit>();

    @JsonIgnore
    @ManyToOne
    public Experiment experiment;

    @Enumerated(EnumType.STRING)
    public Status status;

	  public Boolean hasStarted;

/*
    public static final Long TEST_INSTANCE_ID = 0L;

    public static final ExperimentInstance TEST_INSTANCE = new ExperimentInstance("TESTING");
    static {
        TEST_INSTANCE.status = Status.TESTING;
        TEST_INSTANCE.id = TEST_INSTANCE_ID;
    }
	public final ExperimentInstance TEST_INSTANCE;
*/

    public static enum Status {
        RUNNING, TESTING, STOPPED, FINISHED, ARCHIVED;

        static EnumSet<Status> RUNNABLE = EnumSet.of(RUNNING, TESTING, STOPPED);

        public boolean isRunnable() {
            return RUNNABLE.contains(this);
        }
    }

    @JsonIgnore
    public static Model.Finder<Long, ExperimentInstance> find = new Model.Finder(Long.class, ExperimentInstance.class);

    public static List<ExperimentInstance> findAll() {
        return find.all();
    }

    public AMTHit getHit() {
    	if (amtHits.isEmpty()) {
    		return null;
    	}
    	return amtHits.get(0);
    }

    public ExperimentInstance(String name, Experiment experiment) {
        this.name = name;
        this.experiment = experiment;
        this.creationDate = new Date();
        this.status = Status.STOPPED;
	      this.hasStarted = Boolean.FALSE;

        /* Set up the test instance
        this.TEST_INSTANCE = new ExperimentInstance("TESTING", experiment);
        this.TEST_INSTANCE.status = Status.TESTING;
        this.TEST_INSTANCE.id = TEST_INSTANCE_ID;
        */
    }

    @Override
    public void delete() {
        for (Data d : data) {
            d.delete();
        }
        data.clear();
        for (Event e : events) {
            e.delete();
        }
        events.clear();
        super.delete();
    }

    public void start() {
        if (!status.isRunnable()) {
            throw new IllegalStateException("The current status is not runnable:" + status);
        }
        status = Status.RUNNING;
        this.update();
    }

    public void test() {
        if (!status.isRunnable()) {
            throw new IllegalStateException("The current status is not runnable:" + status);
        }
        status = Status.TESTING;
        this.update();
    }

    public void finish() {
        status = Status.FINISHED;
        this.update();
    }

    public void stop() {
        if (!status.isRunnable()) {
            throw new IllegalStateException("The current status is not runnable:" + status);
        }
        status = Status.STOPPED;
        this.update();
    }

    public void setHasStarted(Boolean hasStarted) {
      this.hasStarted = hasStarted;
      this.update();
    }

	public boolean isTestInstance() {
		return (this.id.equals(Experiment.TEST_INSTANCE_ID));
	}

    public static ExperimentInstance findById(Long id) {
        return find.where().eq("id", id).findUnique();
    }

    public static List<ExperimentInstance> findByStatus(Status status) {
        return find.where().eq("status", status).findList();
    }

    public ObjectNode toJson() {
        ObjectNode experimentInstance = this.toJsonStub();

        ArrayNode jsonData = experimentInstance.putArray("data");
        for (Data d : data) {
            jsonData.add(d.toJson());
        }

        ArrayNode jsonEvents = experimentInstance.putArray("events");
        for (Event e : events) {
            jsonEvents.add(e.toJson());
        }

        ArrayNode jsonAmtHits = experimentInstance.putArray("amtHits");
        for (AMTHit a : amtHits) {
        	jsonAmtHits.add(a.toJson());	
        }

        return experimentInstance;
    }

    public ObjectNode toJsonStub() {
        ObjectNode experimentInstance = Json.newObject();

        experimentInstance.put("id", id);
        experimentInstance.put("creationTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss,S").format(creationDate));
        experimentInstance.put("status", status.toString());
        experimentInstance.put("name", name);
        experimentInstance.put("hitId", (amtHits.size() > 0) ? amtHits.get(0).id.toString() : "");

        ArrayNode jsonHits = experimentInstance.putArray("hits");
        for (AMTHit h : amtHits) {
            jsonHits.add(h.toJson());
        }

        ArrayNode jsonData = experimentInstance.putArray("data");
        for (Data d : data) {
            jsonData.add(d.toJson());
        }

        return experimentInstance;
    }

    public String toString() {
        return "ExperimentInstance(" + id + ")";
    }
}
