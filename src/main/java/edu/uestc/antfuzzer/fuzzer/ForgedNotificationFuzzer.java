package edu.uestc.antfuzzer.fuzzer;

import edu.uestc.antfuzzer.framework.annotation.Before;
import edu.uestc.antfuzzer.framework.annotation.Fuzz;
import edu.uestc.antfuzzer.framework.annotation.Fuzzer;
import edu.uestc.antfuzzer.framework.annotation.Param;
import edu.uestc.antfuzzer.framework.bean.config.framework.FrameworkConfig;
import edu.uestc.antfuzzer.framework.enums.ArgDriver;
import edu.uestc.antfuzzer.framework.enums.FuzzScope;
import edu.uestc.antfuzzer.framework.enums.FuzzingStatus;
import edu.uestc.antfuzzer.framework.enums.ParamType;
import edu.uestc.antfuzzer.framework.exception.AFLException;
import edu.uestc.antfuzzer.framework.type.ArgumentGenerator;
import edu.uestc.antfuzzer.framework.util.EOSUtil;
import edu.uestc.antfuzzer.framework.util.FileUtil;

import java.io.BufferedReader;
import java.io.IOException;


@Fuzzer(vulnerability = "ForgedNotification",
        fuzzScope = FuzzScope.transfer,
        iteration = 100,
        argDriver = ArgDriver.afl,
        useAccountPool = false
)
public class ForgedNotificationFuzzer extends BaseFuzzer {
    private final String forgedNotificationAgentName = "atkforg";
    private final String forgedNotificationTokenFromName = "atkforgfrom";

    private FileUtil.CheckOperation checkOperation;

    @Before
    public boolean init() throws IOException, InterruptedException {
        initFuzzer();
        startUpEOSToken();
        EOSUtil.CppUtil cppUtil = eosUtil.getCppUtil();

        FrameworkConfig.Account account = configUtil.getFrameworkConfig().getAccount();
        // 部署代理合约
        cleosUtil.createAccount(forgedNotificationTokenFromName, account.getPublicKey());
        cleosUtil.pushAction(
                "eosio.token",
                "issue",
                jsonUtil.getJson(
                        forgedNotificationTokenFromName,
                        "10000000.0000 EOS",
                        "FUZZER"),
                "eosio");
        // 设置代理合约账户
        String contractDir = configUtil.getFrameworkConfig().getSmartContracts().getAtkforg();
        cleosUtil.createAccount(forgedNotificationAgentName, account.getPublicKey());
        cppUtil.compileSmartContract(contractDir, smartContract.getName());
        cleosUtil.setContract(forgedNotificationAgentName, contractDir);
        cleosUtil.addCodePermission(forgedNotificationAgentName);
        canAcceptEOS = canAcceptEOS();
        return canAcceptEOS;
    }

    @Fuzz
    public FuzzingStatus fuzz(@Param(ParamType.Action) String action,
                              @Param(ParamType.ArgGenerator) ArgumentGenerator argumentGenerator) throws IOException, InterruptedException, AFLException {
        // 删除opt.txt相关文件
        opUtil.rmOpFile();
        clearLogFiles();
        // 调用代理合约
        cleosUtil.pushAction(
                "eosio.token",
                "transfer",
                jsonUtil.getJson(
                        forgedNotificationTokenFromName,
                        forgedNotificationAgentName,
                        (String) argumentGenerator.generateSpecialTypeArgument("asset"),
                        (String) argumentGenerator.generateSpecialTypeArgument("string")),
                forgedNotificationTokenFromName);

        // 检测opt.txt
        boolean checkResult = fileUtil.checkFile(getCheckOperation(), opUtil.getOpFilePath());
        if (checkResult) {
            environmentUtil.getActionFuzzingResult().getVulnerability().add("ForgedNotification");
            return FuzzingStatus.SUCCESS;
        }
        setResultRecord(action, "ForgedNotificationFuzzer", false);
        return FuzzingStatus.NEXT;
    }


    private FileUtil.CheckOperation getCheckOperation() {
        if (checkOperation == null) {
            checkOperation = new FileUtil.CheckOperation() {
                @Override
                public boolean checkAllLines(BufferedReader reader, Object... args) throws IOException {
                    String target = "CallIndirect";

                    int callIndirect = 0;
                    int equalStr = 0;

                    String attacker = typeUtil.stringToName(forgedNotificationAgentName);
                    String accountName = typeUtil.stringToName(smartContract.getName());

                    String[] requireString = new String[]{
                            generateEqNqString(true, attacker, accountName),
                            generateEqNqString(true, accountName, attacker),
                            generateEqNqString(false, attacker, accountName),
                            generateEqNqString(false, accountName, attacker)
                    };

                    String line = null;
                    while ((line = reader.readLine()) != null) {
                        if (equalStr == 0) {
                            for (String requireStr : requireString) {
                                if (line.contains(requireStr)) {
                                    equalStr++;
                                }
                            }
                        }
                        if (callIndirect < 3 && line.startsWith(target)) {
                            callIndirect++;
                        }
                        if (callIndirect == 3 && equalStr == 1) {
                            return false;
                        }
                    }
                    return callIndirect == 3 && equalStr == 0;
                }

                private String generateEqNqString(boolean isEq, String a, String b) {
                    String format = "%s<%s>(%s,%s)(%s)";
                    return String.format(format, isEq ? "Eq" : "Ne", "uint64_t", a, b, (a.equals(b) ^ !isEq) ? "1" : "0");
                }
            };
        }
        return checkOperation;
    }
}