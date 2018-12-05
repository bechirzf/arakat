package io.github.arakat.arakatcommunity.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mongodb.*;
import com.mongodb.util.JSON;
import io.github.arakat.arakatcommunity.config.AppPropertyValues;
import io.github.arakat.arakatcommunity.exception.GraphNotFoundException;
import io.github.arakat.arakatcommunity.exception.GraphRunFailedException;
import io.github.arakat.arakatcommunity.model.IdSequence;
import io.github.arakat.arakatcommunity.model.TablePath;
import io.github.arakat.arakatcommunity.model.Task;
import io.github.arakat.arakatcommunity.model.response.GraphResponse;
import io.github.arakat.arakatcommunity.utils.FileOperationUtils;
import io.github.arakat.arakatcommunity.utils.MongoConnectionUtils;
import io.github.arakat.arakatcommunity.utils.RequestUtils;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.MongoDbFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;

@Service
public class GraphService {

    private AppPropertyValues appPropertyValues;
    private FileOperationUtils fileOperationUtils;
    private TablePathService tablePathService;
    private TaskService taskService;
    private AppService appService;
    private MongoTemplate mongoTemplate;
    private RequestUtils requestUtils;
    private MongoConnectionUtils mongoConnectionUtils;

    @Autowired
    public GraphService(AppPropertyValues appPropertyValues, FileOperationUtils fileOperationUtils,
                        TablePathService tablePathService, TaskService taskService, AppService appService,
                        MappingMongoConverter mappingMongoConverter, MongoDbFactory mongoDbFactory,
                        RequestUtils requestUtils, MongoConnectionUtils mongoConnectionUtils) {
        this.appPropertyValues = appPropertyValues;
        this.fileOperationUtils = fileOperationUtils;
        this.tablePathService = tablePathService;
        this.taskService = taskService;
        this.appService = appService;
        this.requestUtils = requestUtils;
        this.mongoConnectionUtils = mongoConnectionUtils;
        mongoTemplate = new MongoTemplate(mongoDbFactory, mappingMongoConverter);
    }

    public JSONObject addConfigToDagProperties(String graph) {
        JSONObject graphJson = new JSONObject(graph);

        JSONObject dagProperties = (JSONObject) graphJson.get("dag_properties");

        dagProperties.put("bash_command", appPropertyValues.getBashCommand());
        graphJson.put("dag_properties", dagProperties);

        return graphJson;
    }

    public DBObject addConfigToDagPropertiesDBObject(DBObject graph) {
        DBObject dagProps = (DBObject) graph.get("dag_properties");
        dagProps.put("bash_command", appPropertyValues.getBashCommand());
        graph.put("dag_properties", dagProps);

        return graph;
    }

    public String postGraphAndDagPropsToCore(String graphToPost) {
        String uri = appPropertyValues.getArakatCoreUrl() + ":" + appPropertyValues.getArakatCorePort() + "/" + appPropertyValues.getArakatCorePostingGraphEndpoint();

        return (String) requestUtils.sendPostRequest(uri, graphToPost);
    }

    public void sendGeneratedCodeToAirflow(String dagAndTasks) throws IOException {
        JSONObject dagAndTasksJson = new JSONObject(dagAndTasks);
        JSONObject dagsJson = (JSONObject) dagAndTasksJson.get("codes");

        for (String key : iteratorToIterable(dagsJson.keys())) {
            JSONObject entry = dagsJson.getJSONObject(key);

            for (String taskKey : iteratorToIterable(entry.keys())) {
                if (entry.get(taskKey) instanceof JSONObject) {
                    JSONObject tasks = entry.getJSONObject(taskKey);

                    for (String subKey : iteratorToIterable(tasks.keys())) {
                        String pysparkCode = (String) tasks.get(subKey);
                        String fileName = key + "_" + subKey + ".py";

                        fileOperationUtils.writeToFile(fileName, pysparkCode, appPropertyValues.getSparkCodesOutputFileLocation());
                    }
                } else {
                    String airflowSchedulerCode = (String) entry.get(taskKey);
                    fileOperationUtils.writeToFile(key + ".py", airflowSchedulerCode, appPropertyValues.getDagOutputFileLocation());
                }
            }
        }
    }

    public void checkRunResult(String responseFromCore) throws GraphRunFailedException {
        if (!isGraphSuccessful(responseFromCore)) {
            // TODO: if there is any messages available from the core side, display it as well.
            throw new GraphRunFailedException("");
        }
    }

//    private void getWrittenTopics(String responseFromCore) {
//        JSONObject response = new JSONObject(responseFromCore);
//        JSONObject additionalInfo = (JSONObject) response.get("additional_info");
//        JSONObject writtenTopics = (JSONObject) additionalInfo.get("written_topics");
//
//
//        return getWrittenContentAsList(writtenTopics);
//    }

    public void saveWrittenTablesToDatabase(String responseFromCore) {
        JSONObject response = new JSONObject(responseFromCore);
        JSONObject additionalInfo = (JSONObject) response.get("additional_info");
        JSONObject writtenContent = (JSONObject) additionalInfo.get("written_tables");
        List<Task> tasksToSave = new ArrayList<>();
        List<TablePath> tablesToSave = new ArrayList<>();

        for (String appId : iteratorToIterable(writtenContent.keys())) {
            JSONObject tasks = writtenContent.getJSONObject(appId);

            for (String task : iteratorToIterable(tasks.keys())) {
                JSONArray tables = tasks.getJSONArray(task);

                for (Object table : tables) {
                    String tablePath = new JSONObject(table.toString()).get("table_path").toString();

                    TablePath savedTablePath = tablePathService.saveAndGetTable(tablePath);
                    tablesToSave.add(savedTablePath);
                }

                Task savedTask = taskService.saveAndGetTask(task + "-" + appId, tablesToSave);
                tasksToSave.add(savedTask);
                tablesToSave = new ArrayList<>();
            }

            appService.saveApp(appId, tasksToSave);
            tasksToSave = new ArrayList<>();
        }
    }

    private Boolean isGraphSuccessful(String responseFromCore) {
        JSONObject response = new JSONObject(responseFromCore);

        JSONObject errors = (JSONObject) response.get("errors");
        JSONObject taskErrors = (JSONObject) errors.get("task_errors");
        JSONArray schedulerErrors = (JSONArray) errors.get("scheduler_errors");

        return taskErrors.length() == 0 && schedulerErrors.length() == 0;
    }

    public void saveGraph(String graph) {
        Document document = Document.parse(graph);
        mongoTemplate.insert(document, "graph");
//        DB db = initializeMongoConnection();
//        DBCollection graphCollection = db.getCollection("graph");
//
//        DBObject graphObject = (DBObject) JSON.parse(graph);
//
//        BasicDBObject doc = new BasicDBObject();
//        doc.put("name.first", "First Name");
//        doc.put("name.last", "Last Name");
//        graphCollection.update(new BasicDBObject("_id", "jo"), doc);
//        graphCollection.save(doc);
//        mongoTemplate.insert(doc);
    }

    // TODO: adam id yollamayi unutursa ve graph onceden database'de varsa sikinti
    public String saveTempGraph(String graph) {
        Document document = Document.parse(graph);
        String idToReturn;
        Object graphId = document.get("id");
        if (graphId == null) {
            mongoTemplate.insert(document, "tempGraph");
            idToReturn = document.getObjectId("_id").toString();
        }
        else {
            Query query = new Query(Criteria.where("id").is(graphId.toString()));
            Update update = new Update();
            update.set("graph", document.get("graph"));
            update.set("dag_properties", document.get("dag_properties"));
            mongoTemplate.upsert(query, update, "tempGraph");
            idToReturn = document.getObjectId("id").toString();
        }

        return idToReturn;
    }

    public DBObject loadGraph(String id) throws GraphNotFoundException {
        DBObject resultGraph = getGraphById(id);
        DBObject resultTempGraph = getTempGraphById(id);
        DBObject graphToReturn;

        if (resultGraph == null && resultTempGraph == null) {
            throw new GraphNotFoundException(id);
        } else if (resultGraph == null) {
            graphToReturn = resultTempGraph;
        } else {
            graphToReturn = resultGraph;
        }

        return graphToReturn;
    }

    private DBObject getGraphById(String id) {
        return fetchOneGraph("graph", id);
    }

    private DBObject getTempGraphById(String id) {
        return fetchOneGraph("tempGraph", id);
    }

    private DBObject fetchOneGraph(String collectionName, String id) {
        DB db = mongoConnectionUtils.initializeMongoConnection();
        DBCollection graphCollection = db.getCollection(collectionName);

        BasicDBObject query = new BasicDBObject();
        BasicDBObject fields = new BasicDBObject();

        query.put("_id", new ObjectId(id));
        fields.put("_id", 0);

        return graphCollection.findOne(query, fields);
//        if (cursor != null) {
//            JsonElement element = new JsonPrimitive(JSON.serialize(cursor));
//            return element.getAsJsonObject();
//        }
//
//        return null;
    }

    public List<GraphResponse> getAllGraphs() throws GraphNotFoundException {
        DB db = mongoConnectionUtils.initializeMongoConnection();
        DBCollection graphCollection = db.getCollection("graph");
        DBCollection tempGraphCollection = db.getCollection("tempGraph");
        List<GraphResponse> graphResponseList = new ArrayList<>();

        DBCursor graphs = graphCollection.find();
        DBCursor tempGraphs = tempGraphCollection.find();

        if (graphs == null || tempGraphs == null) {
            throw new GraphNotFoundException("all");
        }

        addToGraphResponse(graphs, graphResponseList);
        addToGraphResponse(tempGraphs, graphResponseList);

        return graphResponseList;
    }

    private void addToGraphResponse(DBCursor dbCursor, List<GraphResponse> graphResponseList) {
        for (DBObject keys : dbCursor) {
            ObjectId objectId = (ObjectId) keys.get("_id");
            BasicDBObject dagProps = (BasicDBObject) keys.get("dag_properties");
            String appId = (String) dagProps.get("app_id");
            graphResponseList.add(new GraphResponse(appId, objectId.toString()));
        }
    }

    private <T> Iterable<T> iteratorToIterable(Iterator<T> iterator) {
        return () -> iterator;
    }
}
