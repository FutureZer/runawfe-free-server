package ru.runa.wfe.lang;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;

import ru.runa.wfe.commons.GroovyScriptExecutor;
import ru.runa.wfe.commons.TypeConversionUtil;
import ru.runa.wfe.commons.Utils;
import ru.runa.wfe.execution.ConvertToSimpleVariables;
import ru.runa.wfe.execution.ConvertToSimpleVariablesContext;
import ru.runa.wfe.execution.ConvertToSimpleVariablesResult;
import ru.runa.wfe.execution.ConvertToSimpleVariablesUnrollContext;
import ru.runa.wfe.execution.CurrentSwimlane;
import ru.runa.wfe.execution.CurrentToken;
import ru.runa.wfe.execution.ExecutionContext;
import ru.runa.wfe.lang.utils.MultiinstanceUtils;
import ru.runa.wfe.task.Task;
import ru.runa.wfe.user.Actor;
import ru.runa.wfe.user.Executor;
import ru.runa.wfe.user.Group;
import ru.runa.wfe.user.TemporaryGroup;
import ru.runa.wfe.user.dao.ExecutorDao;
import ru.runa.wfe.var.MapDelegableVariableProvider;
import ru.runa.wfe.var.MapVariableProvider;
import ru.runa.wfe.var.UserType;
import ru.runa.wfe.var.VariableDefinition;
import ru.runa.wfe.var.VariableMapping;
import ru.runa.wfe.var.dto.WfVariable;
import ru.runa.wfe.var.format.UserTypeFormat;
import ru.runa.wfe.var.format.VariableFormatContainer;

/**
 * is a node that relates to one or more tasks. Property <code>signal</code> specifies how task completion triggers continuation of execution.
 */
public class MultiTaskNode extends BaseTaskNode {
    private static final long serialVersionUID = 1L;
    private MultiTaskCreationMode creationMode;
    private String discriminatorUsage;
    private String discriminatorVariableName;
    private String discriminatorCondition;
    private MultiTaskSynchronizationMode synchronizationMode;
    private final List<VariableMapping> variableMappings = Lists.newArrayList();
    private ExecutionContext previousContext;
    private TemporaryGroup consecutiveGroup;
    private int consecutiveIndex = 0;
    @Autowired
    private transient ExecutorDao executorDao;

    @Override
    public void validate() {
        super.validate();
        Preconditions.checkNotNull(creationMode, "creationMode in " + this);
        Preconditions.checkNotNull(discriminatorVariableName, "discriminatorVariableName in " + this);
        Preconditions.checkNotNull(synchronizationMode, "synchronizationMode in " + this);
    }

    public MultiTaskCreationMode getCreationMode() {
        return creationMode;
    }

    public void setCreationMode(MultiTaskCreationMode creationMode) {
        this.creationMode = creationMode;
    }

    public String getDiscriminatorUsage() {
        return discriminatorUsage;
    }

    public void setDiscriminatorUsage(String discriminatorUsage) {
        this.discriminatorUsage = discriminatorUsage;
    }

    public String getDiscriminatorVariableName() {
        return discriminatorVariableName;
    }

    public void setDiscriminatorVariableName(String discriminatorVariableName) {
        this.discriminatorVariableName = discriminatorVariableName;
    }

    public String getDiscriminatorCondition() {
        return discriminatorCondition;
    }

    public void setDiscriminatorCondition(String discriminatorCondition) {
        this.discriminatorCondition = discriminatorCondition;
    }

    public MultiTaskSynchronizationMode getSynchronizationMode() {
        return synchronizationMode;
    }

    public void setSynchronizationMode(MultiTaskSynchronizationMode executionMode) {
        this.synchronizationMode = executionMode;
    }

    public List<VariableMapping> getVariableMappings() {
        return variableMappings;
    }

    public void setVariableMappings(List<VariableMapping> variableMappings) {
        this.variableMappings.clear();
        this.variableMappings.addAll(variableMappings);
    }

    @Override
    public NodeType getNodeType() {
        return NodeType.MULTI_TASK_STATE;
    }

    @Override
    protected void execute(ExecutionContext executionContext) throws Exception {
    	TaskDefinition taskDefenition = getFirstTaskNotNull();
    	List<?> data = (List<?>) MultiinstanceUtils.parse(executionContext, this).getDiscriminatorValue();
    	if (synchronizationMode == MultiTaskSynchronizationMode.CONSECUTIVE)
    		intitializeCreationContext(executionContext, taskDefenition, data);
        boolean tasksCreated = createTasks(executionContext, taskDefenition, data);
        if (!tasksCreated) {
            log.debug("no tasks were created in " + this);
        }
        // check if we should continue execution
        if (async || !tasksCreated) {
            log.debug("continue execution " + this);
            leave(executionContext);
        }
    }
    
    
    private void intitializeCreationContext(ExecutionContext executionContext, TaskDefinition taskDefinition, List<?> data) {
        VariableMapping discriminatorMapping = new VariableMapping(getDiscriminatorVariableName(), null, getDiscriminatorUsage());
        consecutiveGroup = createTemporaryGroup(executionContext, taskDefinition);
    	if (!discriminatorMapping.isMultiinstanceLinkByVariable() || getCreationMode() == MultiTaskCreationMode.BY_EXECUTORS) {
    		for (Object executorIdentity : new HashSet<Object>(data)) {
                Actor actor = TypeConversionUtil.convertTo(Actor.class, executorIdentity);
                if (actor == null) {
                    log.debug("Executor is null for identity " + executorIdentity);
                    continue;
                }
                executorDao.addExecutorToGroup(actor, consecutiveGroup);
            }
    	} else {
    		CurrentSwimlane swimlane = getInitializedSwimlaneNotNull(executionContext, taskDefinition);
            Executor executor = swimlane.getExecutor();
            if (executor instanceof Group) {
            	Set<Actor> actors = executorDao.getGroupActors((Group)executor);
            	for (Executor actor: actors) {
            		executorDao.addExecutorToGroup(actor, consecutiveGroup);
            	}
            } else if (executor instanceof Actor) {
            	executorDao.addExecutorToGroup(executor, consecutiveGroup);
            } else {
            	log.debug("Executor is not a group or an actor " + executor.toString());
            }
    	}
    }
    
    private TemporaryGroup createTemporaryGroup(ExecutionContext executionContext, TaskDefinition taskDefinition) {
    	try {
    		TemporaryGroup tempGroup = TemporaryGroup.create(taskDefinition.getParsedProcessDefinition().getId(), executionContext.getToken().getId().toString());
    		executorDao.create(tempGroup);
    		return tempGroup;
    	} catch (Exception ex) {
    		log.info(ex.getMessage());
    	}
    	return null;
    }

    private boolean createTasks(ExecutionContext executionContext, TaskDefinition taskDefinition, List<?> data) {
        VariableMapping discriminatorMapping = new VariableMapping(getDiscriminatorVariableName(), null, getDiscriminatorUsage());
        boolean tasksCreated;
        // #305#note-49
        if (synchronizationMode == MultiTaskSynchronizationMode.CONSECUTIVE) {
            List<Executor> consData = new ArrayList<>();
            consData.add(consecutiveGroup);
            data = consData;
        }
        if (!discriminatorMapping.isMultiinstanceLinkByVariable() || getCreationMode() == MultiTaskCreationMode.BY_EXECUTORS) {
            tasksCreated = createTasksByExecutors(executionContext, taskDefinition, data);
        } else {
            tasksCreated = createTasksByDiscriminator(executionContext, taskDefinition, data);
        }
        if (previousContext == null) {
        	MultiinstanceUtils.autoExtendContainerVariables(executionContext, getVariableMappings(),  
        			synchronizationMode == MultiTaskSynchronizationMode.CONSECUTIVE ? executorDao.getGroupActors(consecutiveGroup).size() : data.size());
        }
        previousContext = executionContext;
        return tasksCreated;
    }

    private boolean createTasksByExecutors(ExecutionContext executionContext, TaskDefinition taskDefinition, List<?> data) {
        int tasksCounter = 0;
        for (Object executorIdentity : new HashSet<Object>(data)) {
            Executor executor = TypeConversionUtil.convertTo(Executor.class, executorIdentity);
            if (executor == null) {
                log.debug("Executor is null for identity " + executorIdentity);
                continue;
            }
            if (synchronizationMode == MultiTaskSynchronizationMode.CONSECUTIVE) tasksCounter = consecutiveIndex++;
            taskFactory.create(executionContext, executionContext.getVariableProvider(), taskDefinition, null, executor, tasksCounter, async);
            tasksCounter++;
        }
        return tasksCounter > 0;
    }

    private boolean createTasksByDiscriminator(ExecutionContext executionContext, TaskDefinition taskDefinition, List<?> data) {
        List<Integer> ignoredIndexes = Lists.newArrayList();
        if (!Utils.isNullOrEmpty(discriminatorCondition)) {
            GroovyScriptExecutor scriptExecutor = new GroovyScriptExecutor();
            MapVariableProvider variableProvider = new MapVariableProvider(new HashMap<String, Object>());
            for (int index = 0; index < data.size(); index++) {
                variableProvider.add("item", data.get(index));
                variableProvider.add("index", index);
                boolean result = (Boolean) scriptExecutor.evaluateScript(variableProvider, discriminatorCondition);
                if (!result) {
                    ignoredIndexes.add(index);
                }
            }
            log.debug("Ignored indexes: " + ignoredIndexes);
        }
        int tasksCounter = 0;
        CurrentSwimlane swimlane = getInitializedSwimlaneNotNull(executionContext, taskDefinition);
        Executor executor = synchronizationMode == MultiTaskSynchronizationMode.CONSECUTIVE ? consecutiveGroup : swimlane.getExecutor();
        Map<String, WfVariable> mappedVariableValues = new HashMap<>();
        for (VariableMapping m : getVariableMappings()) {
            WfVariable listVariable = executionContext.getVariableProvider().getVariableNotNull(m.getName());
            mappedVariableValues.put(m.getMappedName(), listVariable);
        }
        for (int index = 0; index < data.size(); index++) {
            if (ignoredIndexes.contains(index)) {
                continue;
            }
            MapDelegableVariableProvider variableProvider = new MapDelegableVariableProvider(new HashMap<>(), executionContext.getVariableProvider());
            variableProvider.add("index", index);
            for (Map.Entry<String, WfVariable> e : mappedVariableValues.entrySet()) {
                WfVariable listVariable = e.getValue();
                List<?> list = (List<?>) listVariable.getValue();
                if (list != null && list.size() > index) {
                    VariableDefinition variableDefinition;
                    UserType userType = ((VariableFormatContainer) listVariable.getDefinition().getFormatNotNull()).getComponentUserType(0);
                    if (userType != null) {
                        variableDefinition = new VariableDefinition(e.getKey(), null, UserTypeFormat.class.getName(), userType);
                    } else {
                        String formatClassName = ((VariableFormatContainer) listVariable.getDefinition().getFormatNotNull()).getComponentClassName(0);
                        variableDefinition = new VariableDefinition(e.getKey(), null, formatClassName, null);
                    }
                    WfVariable variable = new WfVariable(variableDefinition, list.get(index));
                    variableProvider.add(variable);
                    if (variableDefinition.getUserType() != null) {
                        ConvertToSimpleVariablesContext context = new ConvertToSimpleVariablesUnrollContext(variableDefinition, variable.getValueNoDefault());
                        for (ConvertToSimpleVariablesResult unrolled : variableDefinition.getFormatNotNull().processBy(new ConvertToSimpleVariables(), context)) {
                            variableProvider.add(new WfVariable(unrolled.variableDefinition, unrolled.value));
                        }
                    }
                }
            }
            if (synchronizationMode == MultiTaskSynchronizationMode.CONSECUTIVE) index = consecutiveIndex++;
            taskFactory.create(executionContext, variableProvider, taskDefinition, swimlane, executor, index, async);
            tasksCounter++;
        }
        return tasksCounter > 0;
    }

    public boolean isCompletionTriggersSignal(Task task, Actor executor) {
        switch (synchronizationMode) {
            case FIRST:
                return true;
            case LAST:
                return isLastTaskToComplete(task);
            case CONSECUTIVE:
            	return isAllActorsCompletedTask(executor);
            default:
                return false;
        }
    }
    
    private boolean isAllActorsCompletedTask(Actor executor) {
    	executorDao.removeExecutorFromGroup(executor, consecutiveGroup);
    	List<Actor> actors = Lists.newArrayList(executorDao.getGroupActors(consecutiveGroup));
    	if (!actors.isEmpty()) createTasks(previousContext, getFirstTaskNotNull(), actors);
    	else {
    		try {
        		executorDao.remove(consecutiveGroup);
        	} catch (Exception ex) {
        		log.info(ex.getMessage());
        	}
    	}
    	return actors.isEmpty();
    }

    private boolean isLastTaskToComplete(Task task) {
        CurrentToken token = task.getToken();
        boolean lastToComplete = true;
        for (Task other : taskDao.findByToken(token)) {
            if (!other.equals(task)) {
                lastToComplete = false;
                break;
            }
        }
        return lastToComplete;
    }
}
