package dev.toka.protect.command;

import cn.nukkit.command.CommandSender;
import java.util.ArrayList;
import java.util.List;

/**
 * Chat help in pages: "/cmd help [page]". Page 1 is the basics everyone uses; every later page is one topic, introduced by a
 * line saying what it is, and a topic the sender may not use is left out. A page that is not a number shows the first page.
 */
public final class HelpPages {
    public interface Text { String of(CommandSender sender, String key, Object... args); }

    /** @param permission null when everyone may see the section */
    public record Section(String id, String permission, List<String> keys) {}

    private static final int PER_PAGE = 8;
    private final String command;
    private final List<Section> sections;
    private final Text text;

    public HelpPages(String command, List<Section> sections, Text text) {
        this.command = command;
        this.sections = List.copyOf(sections);
        this.text = text;
    }

    /** Each page is the topic line and up to PER_PAGE command lines of one section. */
    private List<List<String>> pages(CommandSender sender) {
        List<List<String>> pages = new ArrayList<>();
        for (Section section : sections) {
            if (section.permission() != null && !sender.hasPermission(section.permission())) continue;
            for (int from = 0; from < section.keys().size(); from += PER_PAGE) {
                List<String> page = new ArrayList<>();
                page.add(text.of(sender, "help.sec." + section.id()));
                for (String key : section.keys().subList(from, Math.min(section.keys().size(), from + PER_PAGE))) page.add(text.of(sender, "help." + key));
                pages.add(page);
            }
        }
        return pages;
    }

    /** @param args what follows the word "help" */
    public void show(CommandSender sender, String[] args) {
        List<List<String>> pages = pages(sender);
        int page = 1;
        if (args.length > 0) try { page = Math.min(Math.max(Integer.parseInt(args[0]), 1), pages.size()); } catch (NumberFormatException ignored) { /* first page */ }
        sender.sendMessage(text.of(sender, "help.title", command, page, pages.size()));
        pages.get(page - 1).forEach(sender::sendMessage);
        if (page < pages.size()) sender.sendMessage(text.of(sender, "help.next", command, page + 1));
    }
}
