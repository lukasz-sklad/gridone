use crate::i18n::{self, Language};

use super::Dialog;

/// Help ▸ About GridCraft: the app, its contributors and the AI models that helped. The credits
/// are compiled in (`crate::credits`, docs/contributors.md).
const REPO_URL: &str = "https://github.com/lukasz-sklad/gridone";

fn summary_text(lang: Language) -> &'static str {
    if lang == Language::Pl {
        "Otwartoźródłowy, natywny arkusz kalkulacyjny w Rust (na bazie GridCraft)."
    } else {
        "A clean-room, open-source, Rust-native spreadsheet (based on GridCraft)."
    }
}

pub(super) fn about(ui: &mut egui::Ui, d: &mut Dialog) {
    let lang = i18n::current(ui.ctx());
    ui.horizontal(|ui| {
        for tab in ["About", "Contributors", "Models"] {
            if ui.selectable_label(d.tab == tab, lang.tr(tab)).clicked() {
                d.tab = tab.to_string();
            }
        }
    });
    ui.separator();
    match d.tab.as_str() {
        "Contributors" => crate::credits::contributors_ui(ui),
        "Models" => crate::credits::models_ui(ui),
        _ => {
            ui.heading("GridCraft");
            ui.label(lang.tr("Version {version}").replace("{version}", env!("CARGO_PKG_VERSION")));
            ui.add_space(4.0);
            ui.label(summary_text(lang));
            ui.hyperlink_to("github.com/lukasz-sklad/gridone", REPO_URL);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn about_copy_excludes_discord_and_artcraft_product_claims() {
        for lang in [Language::En, Language::Pl] {
            let text = summary_text(lang).to_ascii_lowercase();
            assert!(!text.contains("discord"));
            assert!(!text.contains("getartcraft"));
            assert!(!text.contains("part of artcraft"));
        }
        assert!(!REPO_URL.to_ascii_lowercase().contains("discord"));
        assert!(!REPO_URL.to_ascii_lowercase().contains("getartcraft"));
    }
}
