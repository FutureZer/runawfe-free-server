package ru.runa.wfe.task.dto;

import com.google.common.base.Objects;
import java.util.List;
import lombok.val;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.runa.wfe.definition.ProcessDefinitionVersion;
import ru.runa.wfe.definition.dao.ProcessDefinitionLoader;
import ru.runa.wfe.execution.CurrentProcess;
import ru.runa.wfe.execution.ExecutionContext;
import ru.runa.wfe.execution.ProcessHierarchyUtils;
import ru.runa.wfe.execution.dao.CurrentProcessDao;
import ru.runa.wfe.task.Task;
import ru.runa.wfe.user.Actor;
import ru.runa.wfe.user.EscalationGroup;
import ru.runa.wfe.user.Group;
import ru.runa.wfe.user.dao.ExecutorDao;

/**
 * {@link WfTask} factory.
 * 
 * @author Dofs
 * @since 4.0
 */
@Component
public class WfTaskFactory {
    @Autowired
    private ProcessDefinitionLoader processDefinitionLoader;
    @Autowired
    private ExecutorDao executorDao;
    @Autowired
    private CurrentProcessDao currentProcessDao;

    public WfTask create(Task task, Actor targetActor, boolean acquiredBySubstitution, List<String> variableNamesToInclude) {
        return create(task, targetActor, acquiredBySubstitution, variableNamesToInclude, !task.getOpenedByExecutorIds().contains(targetActor.getId()));
    }

    public WfTask create(Task task, Actor targetActor, boolean acquiredBySubstitution, List<String> variableNamesToInclude, boolean firstOpen) {
        CurrentProcess process = task.getProcess();
        Long rootProcessId = ProcessHierarchyUtils.getRootProcessId(process.getHierarchyIds());
        CurrentProcess rootProcess = rootProcessId.equals(process.getId()) ? process : currentProcessDao.get(rootProcessId);
        ProcessDefinitionVersion rootProcessDefinitionVersion = processDefinitionLoader.getDefinition(rootProcess).getProcessDefinitionVersion();
        boolean escalated = false;
        if (task.getExecutor() instanceof EscalationGroup) {
            val escalationGroup = (EscalationGroup) task.getExecutor();
            val originalExecutor = escalationGroup.getOriginalExecutor();
            if (originalExecutor instanceof Group) {
                escalated = !executorDao.isExecutorInGroup(targetActor, (Group) originalExecutor);
            } else {
                escalated = !Objects.equal(originalExecutor, targetActor);
            }
        }
        ProcessDefinitionVersion processDefinitionVersion = processDefinitionLoader.getDefinition(process).getProcessDefinitionVersion();
        WfTask wfTask = new WfTask(task, rootProcessId, rootProcessDefinitionVersion.getId(), rootProcessDefinitionVersion.getDefinition().getName(),
                processDefinitionVersion.getId(), processDefinitionVersion.getDefinition().getName(), targetActor, escalated, acquiredBySubstitution,
                firstOpen);
        if (variableNamesToInclude != null && !variableNamesToInclude.isEmpty()) {
            ExecutionContext executionContext = new ExecutionContext(processDefinitionLoader.getDefinition(process), process);
            for (String variableName : variableNamesToInclude) {
                wfTask.addVariable(executionContext.getVariableProvider().getVariable(variableName));
            }
        }
        return wfTask;
    }
}
