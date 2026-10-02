#!/usr/bin/env python3
from pathlib import Path
import re
import sys


def replace_region(text, start_marker, end_marker, replacement):
    a = text.index(start_marker)
    b = text.index(end_marker, a)
    return text[:a] + replacement + text[b:]


def patch_installer():
    p = Path('app/src/main/java/com/kotyara/chatgptpluscontrol/MainActivity.java')
    s = p.read_text(encoding='utf-8')


    # v13: keep only three player APKs. The installer itself performs central favorites sync.
    old_oncreate = '''        showMainMenu();
    }

    @Override
    protected void onResume()'''
    new_oncreate = '''        showMainMenu();
        ServerSync.schedule(this);
    }

    @Override
    protected void onResume()'''
    if old_oncreate not in s:
        raise SystemExit('installer onCreate block missing')
    s = s.replace(old_oncreate, new_oncreate, 1)

    old_onresume = '''        } else if (stateLine != null) {
            refreshState();
        }
    }

    @Override
    protected void onDestroy()'''
    new_onresume = '''        } else if (stateLine != null) {
            refreshState();
        }
        if (hasFileAccess()) {
            io.execute(() -> {
                try {
                    ServerSync.syncNow(getApplicationContext());
                } catch (Exception ignored) {
                }
            });
        }
    }

    @Override
    protected void onDestroy()'''
    if old_onresume not in s:
        raise SystemExit('installer onResume block missing')
    s = s.replace(old_onresume, new_onresume, 1)

    old_install_done = '''                verifyGame();
                writeMobileConfig();
                runOnUiThread(() -> {'''
    new_install_done = '''                verifyGame();
                writeMobileConfig();
                try {
                    ServerSync.syncNow(getApplicationContext());
                } catch (Exception ignored) {
                }
                runOnUiThread(() -> {'''
    if old_install_done not in s:
        raise SystemExit('installer completion block missing')
    s = s.replace(old_install_done, new_install_done, 1)

    old = 'runOnUiThread(() -> progress.setText("Получено адресов: " + addresses.size() + ". Проверяю ответы…"));'
    if old in s:
        s = s.replace(
            'int total = Math.min(INFO_MAX, items.size());\n            ' + old,
            'int total = Math.min(INFO_MAX, items.size());\n            final int addressCount = addresses.size();\n            runOnUiThread(() -> progress.setText("Получено адресов: " + addressCount + ". Проверяю ответы…"));',
            1,
        )

    old_master_query = '''Set<String> addresses = queryMaster("hl2master.steampowered.com", 27011, MASTER_MAX);
            if (addresses.isEmpty()) addresses = queryMaster("hl1master.steampowered.com", 27010, MASTER_MAX);'''
    new_master_query = '''Set<String> addresses = new LinkedHashSet<>();
            String[][] masters = {
                    {"hl1master.steampowered.com", "27011"},
                    {"ms.tsarvar.com", "27010"},
                    {"ms.gs4u.net", "27010"},
                    {"ds-servers.com", "27010"},
                    {"playmon.ru", "27013"},
                    {"ms3.ip-games.ru", "27010"}
            };
            for (String[] m : masters) {
                if (addresses.size() >= MASTER_MAX) break;
                try {
                    addresses.addAll(queryMaster(m[0], Integer.parseInt(m[1]), MASTER_MAX - addresses.size()));
                } catch (Exception ignored) {
                }
            }'''
    if old_master_query not in s:
        raise SystemExit('launcher master query block missing')
    s = s.replace(old_master_query, new_master_query, 1)


    old_manifest_filter = 'if (!(path.startsWith("valve/") || path.startsWith("cstrike/"))) continue;'
    new_manifest_filter = 'if (!(path.startsWith("valve/") || path.startsWith("cstrike/") || path.equals("Core.dll"))) continue;'
    if old_manifest_filter not in s:
        raise SystemExit('drive manifest filter missing')
    s = s.replace(old_manifest_filter, new_manifest_filter, 1)


    s = replace_region(
        s,
        '    private boolean gameReady() {',
        '    private File xashRoot()',
        '''    private boolean gameReady() {\n        File c = new File(xashRoot(), "cstrike");\n        File v = new File(xashRoot(), "valve");\n        return v.isDirectory() && c.isDirectory() &&\n                new File(xashRoot(), "Core.dll").isFile() &&\n                new File(c, ".cs16_native_ru_v7").isFile() &&\n                new File(c, "liblist.gam").isFile() &&\n                new File(c, "maps").isDirectory() &&\n                new File(c, "models").isDirectory() &&\n                new File(c, "sprites/hud.txt").isFile() &&\n                new File(c, "resource/gameui_russian.txt").isFile() &&\n                new File(c, "resource/BackgroundLayout.txt").isFile() &&\n                new File(c, "resource/background/800_1_a_loading.tga").isFile() &&\n                new File(c, "resource/background/800_3_d_loading.tga").isFile() &&\n                new File(c, "resource/cstrike_russian.txt").isFile() &&\n                new File(c, "touch/gfx/attack.png").isFile() &&\n                new File(c, "touch/gfx/jump.png").isFile() &&\n                new File(c, "touch/gfx/reload.png").isFile() &&\n                new File(c, "touch.cfg").isFile() &&\n                new File(c, "mainui.cfg").isFile();\n    }\n\n''',
    )

    s = replace_region(
        s,
        '    private boolean isCritical(String path) {',
        '    private void verifyGame()',
        '''    private boolean isCritical(String path) {\n        String p = path.toLowerCase(Locale.ROOT);\n        return p.equals("core.dll") ||\n                p.equals("cstrike/liblist.gam") ||\n                p.equals("cstrike/sprites/hud.txt") ||\n                p.equals("cstrike/resource/gameui_russian.txt") ||\n                p.equals("cstrike/resource/serverbrowser_russian.txt") ||\n                p.equals("cstrike/resource/vgui_russian.txt") ||\n                p.equals("valve/resource/valve_russian.txt") ||\n                p.equals("cstrike/resource/backgroundlayout.txt") ||\n                p.startsWith("cstrike/resource/background/");\n    }\n\n''',
    )

    marker = '        requireFile(new File(v, "resource/valve_russian.txt"), "русская локализация Valve");'
    if marker not in s:
        raise SystemExit('verifyGame marker not found')
    extra = '''\n        requireFile(new File(xashRoot(), "Core.dll"), "Core.dll для проверки сервером");\n        requireFile(new File(c, "touch/gfx/attack.png"), "touch/gfx/attack.png");\n        requireFile(new File(c, "touch/gfx/jump.png"), "touch/gfx/jump.png");\n        requireFile(new File(c, "touch/gfx/reload.png"), "touch/gfx/reload.png");\n        requireFile(new File(c, "touch.cfg"), "touch.cfg");\n        requireFile(new File(c, "resource/BackgroundLayout.txt"), "фон меню BackgroundLayout.txt");\n        String[] bg = {\n                "800_1_a_loading.tga", "800_1_b_loading.tga", "800_1_c_loading.tga", "800_1_d_loading.tga",\n                "800_2_a_loading.tga", "800_2_b_loading.tga", "800_2_c_loading.tga", "800_2_d_loading.tga",\n                "800_3_a_loading.tga", "800_3_b_loading.tga", "800_3_c_loading.tga", "800_3_d_loading.tga"\n        };\n        for (String name : bg)\n            requireFile(new File(c, "resource/background/" + name), "фон меню " + name);'''
    s = s.replace(marker, marker + extra, 1)

    method = r'''    private void writeMobileConfig() throws Exception {
        File c = new File(xashRoot(), "cstrike");
        copyAssetTree("cs16extras", c);

        File touchCfg = new File(c, "touch.cfg");
        if (touchCfg.isFile()) {
            byte[] traw = new byte[(int) touchCfg.length()];
            int tn;
            try (FileInputStream in = new FileInputStream(touchCfg)) {
                tn = in.read(traw);
            }
            String tc = new String(traw, 0, Math.max(0, tn), StandardCharsets.UTF_8);
            tc = tc.replace(
                    "touch_addbutton \\\"use\\\" \\\"touch/gfx/use\\\" \\\"+use\\\"",
                    "touch_addbutton \\\"use\\\" \\\"#E\\\" \\\"+use\\\"");
            tc = tc.replace(
                    "touch_addbutton \\\"cmd\\\" \\\"touch/gfx/cmdmenu\\\" \\\"exec touch/cmd/cmd\\\"",
                    "touch_addbutton \\\"cmd\\\" \\\"touch/gfx/cmdmenu\\\" \\\"toggleconsole\\\"");
            try (FileOutputStream out = new FileOutputStream(touchCfg, false)) {
                out.write(tc.getBytes(StandardCharsets.UTF_8));
            }
        }

        File liblist = new File(c, "liblist.gam");
        String ll;
        try (FileInputStream in = new FileInputStream(liblist)) {
            byte[] raw = new byte[(int) liblist.length()];
            int n = in.read(raw);
            ll = new String(raw, 0, Math.max(0, n), StandardCharsets.ISO_8859_1);
        }
        ll = ll.replaceAll("(?im)^\\s*trainmap\\s+\"[^\"]*\"\\s*$", "trainmap \"\"");
        try (FileOutputStream out = new FileOutputStream(liblist, false)) {
            out.write(ll.getBytes(StandardCharsets.ISO_8859_1));
        }

        String mainui =
                "ui_language russian\n" +
                "ui_background_stretch 1\n";
        try (FileOutputStream out = new FileOutputStream(new File(c, "mainui.cfg"), false)) {
            out.write(mainui.getBytes(StandardCharsets.UTF_8));
        }

        String autoexec =
                "// CS16 Android Native RU v7\n" +
                "ui_language russian\n" +
                "ui_background_stretch 1\n" +
                "touch_enable 1\n" +
                "touch_config_file touch.cfg\n" +
                "clearmasters\n" +
                "addmaster hl1master.steampowered.com:27011 gs\n" +
                "addmaster ms.tsarvar.com:27010 gs\n" +
                "addmaster ms.gs4u.net:27010 gs\n" +
                "addmaster ds-servers.com:27010 gs\n" +
                "addmaster playmon.ru:27013 gs\n" +
                "addmaster ms3.ip-games.ru:27010 gs\n" +
                "addmasterstatic https://xash.su/server-list\n" +
                "cl_advertise_engine_in_name 0\n" +
                "cl_ticket_generator revemu2013\n";
        try (FileOutputStream out = new FileOutputStream(new File(c, "autoexec.cfg"), false)) {
            out.write(autoexec.getBytes(StandardCharsets.UTF_8));
        }

        File res = new File(c, "resource");
        if (!res.exists() && !res.mkdirs()) throw new Exception("Не удалось создать cstrike/resource");

        String ru =
                "\"lang\"\n" +
                "{\n" +
                "  \"Language\" \"russian\"\n" +
                "  \"Tokens\"\n" +
                "  {\n" +
                "    \"CS16_NewGame\" \"Новая игра\"\n" +
                "    \"CS16_FindServers\" \"Поиск серверов\"\n" +
                "    \"CS16_Options\" \"Настройки\"\n" +
                "    \"CS16_Quit\" \"Выйти\"\n" +
                "    \"CS16_PlayerSetup\" \"Игрок / ник\"\n" +
                "    \"CS16_Crosshair\" \"Прицел\"\n" +
                "    \"CS16_Controls\" \"Управление\"\n" +
                "    \"CS16_Audio\" \"Аудио\"\n" +
                "    \"CS16_Video\" \"Видео\"\n" +
                "    \"CS16_Done\" \"Назад\"\n" +

                "    \"Cstrike_Cancel\" \"&0 ОТМЕНА\"\n" +
                "    \"Cstrike_CancelLabel\" \"ОТМЕНА\"\n" +
                "    \"Cstrike_OK\" \"&OK\"\n" +
                "    \"Cstrike_Select_Team\" \"Выбор команды\"\n" +
                "    \"Cstrike_Join_Team\" \"ВЫБЕРИТЕ КОМАНДУ\"\n" +
                "    \"Cstrike_Terrorist_Forces\" \"&1 ТЕРРОРИСТЫ\"\n" +
                "    \"Cstrike_CT_Forces\" \"&2 СПЕЦНАЗ\"\n" +
                "    \"Cstrike_VIP_Team\" \"&3 VIP\"\n" +
                "    \"Cstrike_Team_AutoAssign\" \"&5 АВТОВЫБОР\"\n" +
                "    \"Cstrike_Menu_Spectate\" \"&6 НАБЛЮДАТЬ\"\n" +
                "    \"Cstrike_Join_Class\" \"ВЫБЕРИТЕ БОЙЦА\"\n" +
                "    \"Cstrike_Auto_Select\" \"&5 АВТОВЫБОР\"\n" +
                "    \"Cstrike_Terror\" \"&1 PHOENIX\"\n" +
                "    \"Cstrike_L337_Krew\" \"&2 ELITE CREW\"\n" +
                "    \"Cstrike_Arctic\" \"&3 ARCTIC AVENGERS\"\n" +
                "    \"Cstrike_Guerilla\" \"&4 GUERILLA\"\n" +
                "    \"Cstrike_Urban\" \"&1 SEAL TEAM 6\"\n" +
                "    \"Cstrike_GSG9\" \"&2 GSG-9\"\n" +
                "    \"Cstrike_SAS\" \"&3 SAS\"\n" +
                "    \"Cstrike_GIGN\" \"&4 GIGN\"\n" +
                "    \"Cstrike_Spetsnaz\" \"&5 СПЕЦНАЗ\"\n" +
                "    \"Cstrike_Autoselect_Name\" \"СЛУЧАЙНЫЙ ВЫБОР БОЙЦА\"\n" +
                "    \"Cstrike_AutoSelect_Label\" \"Случайный выбор модели игрока.\"\n" +
                "    \"Cstrike_ScoreBoard_Ter\" \"Террористы\"\n" +
                "    \"Cstrike_ScoreBoard_CT\" \"Спецназ\"\n" +
                "    \"Cstrike_DEAD\" \"Мёртв\"\n" +
                "    \"Cstrike_BOMB\" \"Бомба\"\n" +
                "    \"Cstrike_DEFUSE_KIT\" \"Набор сапёра\"\n" +
                "    \"Cstrike_HEALTH\" \"Здоровье\"\n" +
                "    \"Cstrike_ACCOUNT\" \"Деньги\"\n" +
                "    \"Cstrike_Buy_Menu\" \"Меню покупки\"\n" +
                "    \"Cstrike_Select_Category\" \"ВЫБЕРИТЕ КАТЕГОРИЮ\"\n" +
                "    \"Cstrike_Quick_Buy\" \"БЫСТРАЯ ПОКУПКА\"\n" +
                "    \"Cstrike_Current_Money\" \"У ВАС $%s1\"\n" +
                "    \"Cstrike_Pistols\" \"&1 ПИСТОЛЕТЫ\"\n" +
                "    \"Cstrike_Shotguns\" \"&2 ДРОБОВИКИ\"\n" +
                "    \"Cstrike_SubMachineGuns\" \"&3 ПИСТОЛЕТЫ-ПУЛЕМЁТЫ\"\n" +
                "    \"Cstrike_Rifles\" \"&4 ВИНТОВКИ\"\n" +
                "    \"Cstrike_MachineGuns\" \"&5 ПУЛЕМЁТЫ\"\n" +
                "    \"Cstrike_Prim_Ammo\" \"&6 ПАТРОНЫ ОСНОВНОГО ОРУЖИЯ\"\n" +
                "    \"Cstrike_Sec_Ammo\" \"&7 ПАТРОНЫ ПИСТОЛЕТА\"\n" +
                "    \"Cstrike_Equipment\" \"&8 СНАРЯЖЕНИЕ\"\n" +
                "    \"Join game\" \"Подключиться\"\n" +
                "    \"View game info\" \"Информация о сервере\"\n" +
                "    \"Favorite\" \"В избранное\"\n" +
                "    \"Refresh\" \"Обновить\"\n" +
                "    \"Add server\" \"Добавить сервер\"\n" +
                "    \"Done\" \"Готово\"\n" +
                "    \"Direct\" \"Интернет\"\n" +
                "    \"Favorites\" \"Избранное\"\n" +
                "    \"History\" \"История\"\n" +
                "    \"Name\" \"Сервер\"\n" +
                "    \"GameUI_Map\" \"Карта\"\n" +
                "    \"Players\" \"Игроки\"\n" +
                "    \"Ping\" \"Пинг\"\n" +
                "    \"GameUI_GameMenu_CreateServer\" \"Создать сервер\"\n" +
                "    \"GameUI_RandomMap\" \"< Случайная карта >\"\n" +
                "    \"GameUI_OK\" \"ОК\"\n" +
                "    \"GameUI_Cancel\" \"Отмена\"\n" +
                "    \"GameUI_Apply\" \"Применить\"\n" +
                "    \"GameUI_Options\" \"Настройки\"\n" +
                "    \"GameUI_Multiplayer\" \"Поиск серверов\"\n" +
                "    \"GameUI_NewGame\" \"Новая игра\"\n" +
                "    \"GameUI_GameMenu_Quit\" \"Выйти\"\n" +
                "    \"GameUI_QuitConfirmationText\" \"Выйти из игры?\"\n" +
                "    \"StringsList_188\" \"Вернуться в игру\"\n" +
                "    \"StringsList_189\" \"Создать локальную игру\"\n" +
                "    \"StringsList_193\" \"Изменить настройки игры и управления\"\n" +
                "    \"StringsList_194\" \"\"\n" +
                "    \"StringsList_198\" \"Найти сервер Counter-Strike\"\n" +
                "    \"Join a network game will exit any current game, OK to exit?\" \"Подключение к серверу завершит текущую игру. Продолжить?\"\n" +
                "  }\n" +
                "}\n";
        try (FileOutputStream out = new FileOutputStream(new File(res, "cstrike_russian.txt"), false)) {
            out.write(ru.getBytes(StandardCharsets.UTF_8));
        }

        try (FileOutputStream out = new FileOutputStream(new File(c, ".cs16_native_ru_v7"), false)) {
            out.write("7\n".getBytes(StandardCharsets.US_ASCII));
        }
    }

    private void copyAssetTree(String assetPath, File dest) throws Exception {
        String[] children = getAssets().list(assetPath);
        if (children == null) throw new Exception("Не удалось прочитать встроенные touch-файлы: " + assetPath);
        if (children.length == 0) {
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs())
                throw new Exception("Не удалось создать папку " + parent);
            try (InputStream in = getAssets().open(assetPath);
                 FileOutputStream out = new FileOutputStream(dest, false)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            return;
        }
        if (!dest.exists() && !dest.mkdirs())
            throw new Exception("Не удалось создать папку " + dest);
        for (String child : children)
            copyAssetTree(assetPath + "/" + child, new File(dest, child));
    }

'''
    s = replace_region(s, '    private void writeMobileConfig() {', '    private void launchClient(String server)', method)

    # v13: voice is enabled by default and the clean-install player name is KoT46_ Player.
    s = s.replace(
        '                "cl_ticket_generator revemu2013\\\\n";',
        '                "cl_ticket_generator revemu2013\\\\n" +\\n'
        '                "voice_enable 1\\\\n" +\\n'
        '                "voice_modenable 1\\\\n" +\\n'
        '                "sv_voiceenable 1\\\\n";',
        1,
    )

    autoexec_written = '''        try (FileOutputStream out = new FileOutputStream(new File(c, "autoexec.cfg"), false)) {
            out.write(autoexec.getBytes(StandardCharsets.UTF_8));
        }

        File res = new File(c, "resource");'''
    name_init = r'''        try (FileOutputStream out = new FileOutputStream(new File(c, "autoexec.cfg"), false)) {
            out.write(autoexec.getBytes(StandardCharsets.UTF_8));
        }

        File playerCfg = new File(c, "config.cfg");
        if (playerCfg.isFile()) {
            byte[] raw = new byte[(int) playerCfg.length()];
            int n;
            try (FileInputStream in = new FileInputStream(playerCfg)) {
                n = in.read(raw);
            }
            String cfg = new String(raw, 0, Math.max(0, n), StandardCharsets.ISO_8859_1);
            String[] lines = cfg.replace("\r", "").split("\n", -1);
            StringBuilder fixed = new StringBuilder();
            boolean foundName = false;
            for (String line : lines) {
                String trim = line.trim();
                if (!foundName && trim.startsWith("name ")) {
                    fixed.append("name ").append((char) 34).append("KoT46_ Player").append((char) 34).append('\n');
                    foundName = true;
                } else if (!line.isEmpty()) {
                    fixed.append(line).append("\\n");
                }
            }
            if (!foundName) fixed.append("name ").append((char) 34).append("KoT46_ Player").append((char) 34).append('\n');
            try (FileOutputStream out = new FileOutputStream(playerCfg, false)) {
                out.write(fixed.toString().getBytes(StandardCharsets.ISO_8859_1));
            }
        }

        File res = new File(c, "resource");'''
    if autoexec_written not in s:
        raise SystemExit('generated autoexec block missing')
    s = s.replace(autoexec_written, name_init, 1)

    s = s.replace('Executors.newFixedThreadPool(3)', 'Executors.newFixedThreadPool(2)')
    p.write_text(s, encoding='utf-8')


def patch_client():
    gp = Path('cs16/android/app/build.gradle')
    g = gp.read_text(encoding='utf-8')
    if 'applicationId = "su.xash.cs16client"' in g:
        g = g.replace('applicationId = "su.xash.cs16client"', 'applicationId = "com.kotyara.cs16client"')
    gp.write_text(g, encoding='utf-8')


    tp = Path('cs16/3rdparty/cs16client-extras/touch.cfg')
    ts = tp.read_text(encoding='utf-8')
    ts = ts.replace(
        'touch_addbutton "use" "touch/gfx/use" "+use"',
        'touch_addbutton "use" "#E" "+use"',
        1,
    )
    ts = ts.replace(
        'touch_addbutton "cmd" "touch/gfx/cmdmenu" "exec touch/cmd/cmd"',
        'touch_addbutton "cmd" "touch/gfx/cmdmenu" "toggleconsole"',
        1,
    )
    tp.write_text(ts, encoding='utf-8')

    p = Path('cs16/3rdparty/mainui_cpp/menus/Main.cpp')
    s = p.read_text(encoding='utf-8')
    replacements = {
        'newGame.SetNameAndStatus( L( "GameUI_NewGame" ), L( "StringsList_189" ) );':
            'newGame.SetNameAndStatus( L( "GameUI_GameMenu_NewGame" ), L( "StringsList_189" ) );',
        'newGame.onReleased = UI_NewGame_Menu;':
            'newGame.onReleased = UI_CreateGame_Menu;',
        'multiPlayer.SetNameAndStatus( L( "GameUI_Multiplayer" ), L( "StringsList_198" ) );':
            'multiPlayer.SetNameAndStatus( L( "GameUI_GameMenu_FindServers" ), L( "StringsList_198" ) );',
        'configuration.SetNameAndStatus( L( "GameUI_Options" ), L( "StringsList_193" ) );':
            'configuration.SetNameAndStatus( L( "GameUI_GameMenu_Options" ), L( "StringsList_193" ) );',
        'quit.SetNameAndStatus( L( "GameUI_GameMenu_Quit" ), L( "GameUI_QuitConfirmationText" ) );':
            'quit.SetNameAndStatus( L( "GameUI_GameMenu_Quit" ), L( "GameUI_QuitConfirmationText" ) );',
    }
    for old, new in replacements.items():
        if old not in s:
            raise SystemExit('Main.cpp expected line missing: ' + old)
        s = s.replace(old, new, 1)

    # Open the actual server browser directly. Current Velaron mainui exposes it
    # through UI_InternetGames_Menu rather than the old GameUI wrapper.
    if 'multiPlayer.onReleased = UI_MultiPlayer_Menu;' in s:
        s = s.replace('multiPlayer.onReleased = UI_MultiPlayer_Menu;', 'multiPlayer.onReleased = UI_InternetGames_Menu;', 1)

    # Keep New Game available for CS (liblist.gam is multiplayer_only).
    check_block = '''\tif( !EngFuncs::CheckGameDll( ))\n\t{\n\t\tsaveRestore.SetGrayed( true );\n\t\thazardCourse.SetGrayed( true );\n\t\tnewGame.SetGrayed( true );\n\t}\n'''
    if check_block not in s:
        raise SystemExit('Main.cpp CheckGameDll block missing')
    s = s.replace(check_block, check_block + '\n\tnewGame.SetGrayed( false );\n', 1)

    add_start = s.index('\tAddItem( banner );', s.index('void CMenuMain::_Init'))
    add_end = s.index('\n}', add_start)
    add_block = '''\tAddItem( banner );\n\tAddItem( newGame );\n\tAddItem( multiPlayer );\n\tAddItem( configuration );\n\tAddItem( quit );'''
    s = s[:add_start] + add_block + s[add_end:]

    a = s.index('void CMenuMain::VidInit( bool connected )')
    b = s.index('void CMenuMain::_VidInit()', a)
    s = s[:a] + '''void CMenuMain::VidInit( bool connected )
{
\tconst int x = 18;
\tconst int gap = 38;
\tint y = 610;

\tnewGame.SetCoord( x, y ); y += gap;
\tmultiPlayer.SetCoord( x, y ); y += gap;
\tconfiguration.SetCoord( x, y ); y += gap;
\tquit.SetCoord( x, y );

\tnewGame.SetVisibility( true );
\tmultiPlayer.SetVisibility( true );
\tconfiguration.SetVisibility( true );
\tquit.SetVisibility( true );
}

''' + s[b:]
    p.write_text(s, encoding='utf-8')

    bp = Path('cs16/3rdparty/mainui_cpp/controls/BackgroundBitmap.cpp')
    bs = bp.read_text(encoding='utf-8')
    # Remove the mobile port build watermark while preserving menu drawing.
    bs = re.sub(
        r'\n\t// print CS16Client version.*?(?=\n\})',
        '',
        bs,
        count=1,
        flags=re.S,
    )
    bp.write_text(bs, encoding='utf-8')


    cp = Path('cs16/3rdparty/mainui_cpp/menus/Configuration.cpp')
    cs = cp.read_text(encoding='utf-8')
    old_cfg = '''\tAddItem( banner );
\tAddButton( L( "Controls" ), L( "Change keyboard, mouse, touch and joystick settings." ),
\t\tPC_CONTROLS, UI_Controls_Menu, QMF_NOTIFY );
\tAddButton( L( "GameUI_Audio" ), L( "Change sound volume and quality." ),
\t\tPC_AUDIO, UI_Audio_Menu, QMF_NOTIFY );
\tAddButton( L( "GameUI_Video" ), L( "Change screen size, video mode, gamme and glare reduction." ),
\t\tPC_VIDEO, UI_Video_Menu, QMF_NOTIFY );
\tAddButton( L( "Done" ), L( "Go back to the Main Menu." ),
\t\tPC_DONE, VoidCb( &CMenuOptions::Hide ), QMF_NOTIFY );'''
    new_cfg = '''\tAddItem( banner );
\tAddButton( L( "CS16_PlayerSetup" ), nullptr,
\t\tPC_CUSTOMIZE, UI_PlayerSetup_Menu, QMF_NOTIFY );
\tAddButton( L( "CS16_Crosshair" ), nullptr,
\t\tPC_CROSSHAIR, UI_Crosshair_Menu, QMF_NOTIFY );
\tAddButton( L( "CS16_Controls" ), nullptr,
\t\tPC_CONTROLS, UI_Controls_Menu, QMF_NOTIFY );
\tAddButton( L( "CS16_Audio" ), nullptr,
\t\tPC_AUDIO, UI_Audio_Menu, QMF_NOTIFY );
\tAddButton( L( "CS16_Video" ), nullptr,
\t\tPC_VIDEO, UI_Video_Menu, QMF_NOTIFY );
\tAddButton( L( "CS16_Done" ), nullptr,
\t\tPC_DONE, VoidCb( &CMenuOptions::Hide ), QMF_NOTIFY );'''
    if old_cfg not in cs:
        raise SystemExit('Configuration.cpp expected block missing')
    cs = cs.replace(old_cfg, new_cfg, 1)
    cp.write_text(cs, encoding='utf-8')

    java = Path('cs16/android/app/src/main/java/su/xash/cs16client/MainActivity.java')
    java.write_text(r'''package su.xash.cs16client;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String engine = "su.xash.engine.test";
        try {
            getPackageManager().getPackageInfo(engine, 0);
        } catch (PackageManager.NameNotFoundException e) {
            engine = "su.xash.engine";
            try {
                getPackageManager().getPackageInfo(engine, 0);
            } catch (PackageManager.NameNotFoundException ex) {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/FWGS/xash3d-fwgs/releases/tag/continuous")));
                finish();
                return;
            }
        }

        String argv = "-console +ui_language russian +ui_background_stretch 1 +touch_enable 1 +touch_config_file touch.cfg -dll @yapb " +
                "+cl_advertise_engine_in_name 0 +cl_ticket_generator revemu2013";
        String server = getIntent().getStringExtra("server");
        if (server != null && server.matches("^[A-Za-z0-9._-]+:[0-9]{1,5}$")) {
            argv += " +connect " + server + " gs";
        }

        Intent game = new Intent().setComponent(
                new ComponentName(engine, "su.xash.engine.XashActivity"))
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("gamedir", "cstrike")
                .putExtra("gamelibdir", getApplicationInfo().nativeLibraryDir)
                .putExtra("argv", argv)
                .putExtra("package", getPackageName());
        startActivity(game);
        finish();
    }
}
''', encoding='utf-8')


if __name__ == '__main__':
    if len(sys.argv) != 2 or sys.argv[1] not in {'installer', 'client'}:
        raise SystemExit('usage: patch_cs16_native_v2.py installer|client')
    if sys.argv[1] == 'installer':
        patch_installer()
    else:
        patch_client()
