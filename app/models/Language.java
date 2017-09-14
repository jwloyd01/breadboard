package models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import play.db.ebean.Model;
import play.libs.Json;

import javax.persistence.*;
import java.util.List;

@Entity
@Table(name = "languages")
public class Language extends Model {
  @Id
  public Long id;

  public String code;

  public String name;

  @JsonIgnore
  public static Finder<Long, Language> find = new Finder(Long.class, Language.class);

  @JsonIgnore
  public static List<Language> findAll() {
    return find.all();
  }

  @JsonIgnore
  public Language() {}

  @JsonIgnore
  public ObjectNode toJson() {
    ObjectNode language = Json.newObject();
    language.put("id", id);
    language.put("code", code);
    language.put("name", name);
    return language;
  }

  @JsonIgnore
  public String toString() {
    return "Language(" + id + ")";
  }
}
