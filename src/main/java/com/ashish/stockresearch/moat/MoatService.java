package com.ashish.stockresearch.moat;

import com.ashish.stockresearch.screening.FundamentalsSnapshot;
import com.ashish.stockresearch.trace.Progress;
import org.springframework.stereotype.Service;

/**
 * Answers "does this company have a lasting edge, and why would investors pay more for it?" from a snapshot already
 * captured for the company: the signs in its own figures ({@link MoatAssessor}), its price beside its peers, and what
 * its own documents say ({@link CompanyStatements}), rendered in Java ({@link MoatRenderer}).
 */
@Service
public class MoatService {

    private final MoatAssessor assessor;
    private final CompanyStatements statements;
    private final MoatRenderer renderer;
    private final MoatProperties settings;

    public MoatService(MoatAssessor assessor, CompanyStatements statements, MoatRenderer renderer,
                       MoatProperties settings) {
        this.assessor = assessor;
        this.statements = statements;
        this.renderer = renderer;
        this.settings = settings;
    }

    public String answer(FundamentalsSnapshot snapshot) {
        MoatSigns signs = assessor.assess(snapshot);
        String name = snapshot.companyName() == null ? snapshot.symbol() : snapshot.companyName();
        CompanyStatements.Found found = Progress.step("Searching %s's own documents for what it says about its position"
                .formatted(name), () -> statements.about(snapshot.symbol(), settings.maxStatements()));
        return renderer.render(signs, assessor.whyNoSigns(signs), found);
    }
}
