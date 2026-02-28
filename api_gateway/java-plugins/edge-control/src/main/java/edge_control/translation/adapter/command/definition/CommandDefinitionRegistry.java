package edge_control.translation.adapter.command.definition;

public interface CommandDefinitionRegistry {

    CommandDefinition get(String commandName);
}