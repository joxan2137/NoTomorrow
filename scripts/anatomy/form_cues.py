#!/usr/bin/env python3
"""Generates `form_cues.json`: short coaching cues and common mistakes for the exercise sheet, in English and Polish.

Original text. `add` writes one entry shared by every listed id; `alias` gives more ids the entry of an existing one,
for variants the same cues fit exactly (another grip, a machine version). Leave an exercise out rather than give it
cues that are only roughly right.

Run `python3 scripts/anatomy/form_cues.py` to rewrite both copies (iOS resources and Android assets).
"""
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
TARGETS = [ROOT / "NoTomorrow/Resources/form_cues.json", ROOT / "android/app/src/main/assets/form_cues.json"]
C = {}


def add(ids, en_c, en_m, pl_c, pl_m):
    assert len(en_c) == len(pl_c) and len(en_m) == len(pl_m), ids
    for i in ids.split():
        assert i not in C, f"{i} listed twice"
        C[i] = {"en": {"cues": en_c, "mistakes": en_m}, "pl": {"cues": pl_c, "mistakes": pl_m}}


def alias(source, ids):
    for i in ids.split():
        assert i not in C, f"{i} listed twice"
        C[i] = C[source]


add("Barbell_Squat Smith_Machine_Squat",
 ["Brace your core before each rep: big breath into the belly, then hold it.",
  "Push your knees out in line with your toes as you sit down.",
  "Keep the bar over the middle of your foot the whole way.",
  "Go as deep as you can while your lower back stays neutral."],
 ["Knees caving in on the way up.", "Heels lifting off the floor.", "Chest dropping so the squat turns into a good morning."],
 ["Przed każdym powtórzeniem napnij brzuch: głęboki wdech w brzuch i zatrzymaj powietrze.",
  "Schodząc w dół, wypychaj kolana na zewnątrz, w linii palców stóp.",
  "Sztanga przez cały ruch nad środkiem stopy.",
  "Schodź tak nisko, jak pozwala neutralny odcinek lędźwiowy."],
 ["Kolana uciekające do środka przy wstawaniu.", "Odrywanie pięt od podłogi.", "Opadanie klatki, przez które przysiad zamienia się w skłon."])

add("Front_Barbell_Squat",
 ["Keep your elbows high so the bar sits on your shoulders, not your hands.",
  "Stay upright: think of sitting straight down between your heels.",
  "Brace hard before you descend and keep the breath until you're past the hardest part."],
 ["Elbows dropping, which rolls the bar forward.", "Rounding the upper back at the bottom."],
 ["Trzymaj łokcie wysoko, żeby sztanga leżała na barkach, a nie na dłoniach.",
  "Zostań wyprostowany: myśl o siadaniu prosto w dół między piętami.",
  "Mocno napnij tułów przed zejściem i trzymaj powietrze aż miniesz najtrudniejszy moment."],
 ["Opadające łokcie, przez które sztanga zjeżdża do przodu.", "Zaokrąglanie górnej części pleców na dole."])

add("Goblet_Squat",
 ["Hold the weight against your chest with your elbows pointing down.",
  "Sit between your heels and let your elbows brush the inside of your knees.",
  "Drive through the whole foot to stand up."],
 ["Letting the weight drift away from your chest.", "Rising onto your toes."],
 ["Trzymaj ciężar przy klatce, łokcie skierowane w dół.",
  "Siadaj między piętami, łokcie mogą muskać wewnętrzną stronę kolan.",
  "Wstając, odpychaj się całą stopą."],
 ["Odsuwanie ciężaru od klatki.", "Wspinanie się na palce."])

add("Hack_Squat nt_pendulum_squat nt_belt_squat",
 ["Set your feet so your knees track over your toes at the bottom.",
  "Keep your back and hips pressed into the pad.",
  "Lower under control and don't bounce out of the bottom."],
 ["Locking the knees hard at the top.", "Hips peeling off the pad as you go deep."],
 ["Ustaw stopy tak, żeby na dole kolana szły nad palcami.",
  "Plecy i biodra dociśnięte do oparcia.",
  "Opuszczaj się kontrolowanie i nie odbijaj się z dołu."],
 ["Gwałtowne blokowanie kolan na górze.", "Odrywanie bioder od oparcia przy głębokim zejściu."])

add("Leg_Press",
 ["Place your feet shoulder-width, mid-platform.",
  "Lower until your knees are around 90° or your hips start to lift, whichever comes first.",
  "Press through your heels and midfoot."],
 ["Lower back rounding off the seat at the bottom.", "Snapping the knees straight at the top."],
 ["Stopy na szerokość barków, na środku platformy.",
  "Opuszczaj do około 90° w kolanach albo do momentu, gdy biodra zaczynają się odrywać, co nastąpi pierwsze.",
  "Wypychaj piętami i środkiem stopy."],
 ["Odrywanie lędźwi od siedziska na dole.", "Gwałtowne prostowanie kolan na górze."])

add("Barbell_Deadlift",
 ["Bar over the middle of your foot, shins close to it.",
  "Pull the slack out of the bar and set your lats before it leaves the floor.",
  "Push the floor away with your legs, then drive your hips through.",
  "Keep the bar dragging close to your legs the whole way."],
 ["Rounding the lower back.", "Jerking the bar off the floor.", "Leaning back at lockout."],
 ["Sztanga nad środkiem stopy, piszczele blisko niej.",
  "Zanim sztanga oderwie się od podłogi, napnij ją i spinaj najszersze grzbietu.",
  "Odpychaj podłogę nogami, potem dopchnij biodra.",
  "Przez cały ruch prowadź sztangę blisko nóg."],
 ["Zaokrąglanie odcinka lędźwiowego.", "Szarpanie sztangi z podłogi.", "Odchylanie się do tyłu w zablokowaniu."])

add("Sumo_Deadlift",
 ["Take a wide stance with your toes turned out and grip inside your knees.",
  "Push your knees out over your toes before you pull.",
  "Keep your chest up and your hips close to the bar."],
 ["Hips shooting up first so it becomes a stiff-leg pull.", "Knees caving in."],
 ["Szeroki rozstaw, palce stóp na zewnątrz, chwyt wewnątrz kolan.",
  "Przed ciągiem wypchnij kolana nad palce stóp.",
  "Klatka w górze, biodra blisko sztangi."],
 ["Biodra wystrzeliwujące pierwsze, przez co ciąg robi się na prostych nogach.", "Kolana uciekające do środka."])

add("Romanian_Deadlift Stiff-Legged_Barbell_Deadlift nt_b_stance_romanian_deadlift",
 ["Keep a soft bend in the knees and push your hips back.",
  "Slide the weight down your thighs, staying close to your legs.",
  "Stop when your hamstrings are fully stretched, before your back rounds.",
  "Squeeze your glutes to stand up."],
 ["Rounding the back to reach lower.", "Turning it into a squat by bending the knees more."],
 ["Lekko ugięte kolana, biodra cofaj do tyłu.",
  "Prowadź ciężar blisko nóg, po udach w dół.",
  "Zatrzymaj się, gdy dwugłowe uda są w pełni rozciągnięte, zanim zaokrąglą się plecy.",
  "Wstając, napnij pośladki."],
 ["Zaokrąglanie pleców, żeby zejść niżej.", "Zamienianie ruchu w przysiad przez mocniejsze uginanie kolan."])

add("Barbell_Bench_Press_-_Medium_Grip Barbell_Incline_Bench_Press_-_Medium_Grip Close-Grip_Barbell_Bench_Press",
 ["Pull your shoulder blades back and down and keep them there.",
  "Plant your feet and keep your glutes on the bench.",
  "Lower the bar to your lower chest with elbows about 45° from your body.",
  "Press up and slightly back over your shoulders."],
 ["Flaring the elbows out to 90°.", "Bouncing the bar off your chest.", "Lifting your hips off the bench."],
 ["Ściągnij łopatki do tyłu i w dół i trzymaj je tak.",
  "Stopy mocno na ziemi, pośladki na ławce.",
  "Opuszczaj sztangę na dolną część klatki, łokcie około 45° od tułowia.",
  "Wypychaj w górę i lekko do tyłu, nad barki."],
 ["Łokcie rozłożone na 90°.", "Odbijanie sztangi od klatki.", "Odrywanie bioder od ławki."])

add("Dumbbell_Bench_Press Incline_Dumbbell_Press nt_hs_iso_lateral_bench_press nt_hs_iso_lateral_incline_press Leverage_Chest_Press",
 ["Keep your shoulder blades pinned to the bench.",
  "Lower until you feel a stretch across your chest, elbows slightly tucked.",
  "Press up and slightly together without clanking the weights."],
 ["Letting the shoulders roll forward at the top.", "Dropping the weights too fast into the stretch."],
 ["Łopatki przyklejone do ławki.",
  "Opuszczaj do odczucia rozciągnięcia klatki, łokcie lekko przy tułowiu.",
  "Wypychaj w górę i lekko do środka, bez stukania ciężarami."],
 ["Wysuwanie barków do przodu na górze.", "Zbyt szybkie opuszczanie w rozciągnięcie."])

add("Standing_Military_Press",
 ["Squeeze your glutes and brace so your lower back doesn't arch.",
  "Start with the bar on your upper chest, forearms vertical.",
  "Move your head back to let the bar pass, then push your head through at the top."],
 ["Leaning back into a standing incline press.", "Pressing the bar out in front instead of straight up."],
 ["Napnij pośladki i brzuch, żeby nie wyginać lędźwi.",
  "Zacznij ze sztangą na górze klatki, przedramiona pionowo.",
  "Cofnij głowę, żeby przepuścić sztangę, a na górze wsuń ją pod sztangę."],
 ["Odchylanie się, przez co wychodzi wyciskanie skośne na stojąco.", "Wypychanie sztangi przed siebie zamiast prosto w górę."])

add("Dumbbell_Shoulder_Press Arnold_Dumbbell_Press Machine_Shoulder_Military_Press nt_hs_iso_lateral_shoulder_press",
 ["Keep your ribs down and your back against the pad.",
  "Lower until your hands are around ear height.",
  "Press straight up, forearms vertical."],
 ["Arching the lower back.", "Cutting the range short at the bottom."],
 ["Żebra w dół, plecy przy oparciu.",
  "Opuszczaj, aż dłonie będą mniej więcej na wysokości uszu.",
  "Wypychaj prosto w górę, przedramiona pionowo."],
 ["Wyginanie odcinka lędźwiowego.", "Skracanie ruchu na dole."])

add("Bent_Over_Barbell_Row",
 ["Hinge until your torso is about 45° or lower, with a flat back.",
  "Pull the bar to your lower ribs, leading with your elbows.",
  "Squeeze your shoulder blades together at the top."],
 ["Standing up more with every rep.", "Using your hips to swing the weight up."],
 ["Pochyl się do około 45° lub niżej, plecy proste.",
  "Przyciągaj sztangę do dolnych żeber, prowadząc łokciami.",
  "Na górze ściągnij łopatki."],
 ["Prostowanie się z każdym powtórzeniem.", "Zarzucanie ciężaru biodrami."])

add("One-Arm_Dumbbell_Row nt_chest_supported_dumbbell_row nt_single_arm_landmine_row",
 ["Keep your back flat and your hips square to the floor.",
  "Pull the weight toward your hip, not your armpit.",
  "Let your shoulder blade stretch forward at the bottom."],
 ["Twisting the torso to lift more.", "Shrugging the weight up with the trap."],
 ["Plecy proste, biodra równolegle do podłogi.",
  "Przyciągaj ciężar w stronę biodra, nie pachy.",
  "Na dole pozwól łopatce wysunąć się do przodu."],
 ["Skręcanie tułowia, żeby podnieść więcej.", "Podciąganie ciężaru barkiem jak przy szrugsach."])

add("Seated_Cable_Rows nt_hs_select_seated_row",
 ["Sit tall with a slight bend in the knees.",
  "Pull the handle to your belly and squeeze your shoulder blades.",
  "Let your arms extend fully and your shoulders reach forward on the way back."],
 ["Rocking the torso back and forth.", "Shrugging your shoulders to your ears."],
 ["Siedź prosto, kolana lekko ugięte.",
  "Przyciągaj uchwyt do brzucha i ściągaj łopatki.",
  "Wracając, wyprostuj ręce i pozwól barkom wysunąć się do przodu."],
 ["Bujanie tułowiem w przód i w tył.", "Unoszenie barków do uszu."])

add("Wide-Grip_Lat_Pulldown nt_hs_select_lat_pulldown nt_hs_iso_lateral_front_lat_pulldown",
 ["Lean back slightly and keep your chest up.",
  "Pull the bar to your upper chest by driving your elbows down.",
  "Control the bar all the way up until your arms are straight."],
 ["Pulling the bar behind your neck.", "Leaning far back and using body weight to move the bar."],
 ["Lekko odchyl się do tyłu, klatka w górze.",
  "Ściągaj drążek do górnej części klatki, prowadząc łokcie w dół.",
  "Kontroluj drążek w górę aż do wyprostu rąk."],
 ["Ściąganie drążka za głowę.", "Mocne odchylanie się i ciągnięcie masą ciała."])

add("Pullups Chin-Up",
 ["Start from a dead hang with your shoulders pulled down.",
  "Pull your chest toward the bar, elbows driving down and back.",
  "Lower all the way down under control."],
 ["Kipping or swinging to get over the bar.", "Half reps that stop short of straight arms."],
 ["Zacznij ze zwisu, barki ściągnięte w dół.",
  "Przyciągaj klatkę do drążka, łokcie w dół i do tyłu.",
  "Opuszczaj się kontrolowanie aż do końca."],
 ["Zamachy i bujanie, żeby przejść nad drążek.", "Połówki powtórzeń bez wyprostu rąk."])

add("Dips_-_Triceps_Version nt_hs_seated_dip nt_hs_select_assisted_dip",
 ["Keep your shoulders down and away from your ears.",
  "Lower until your upper arms are about parallel to the floor.",
  "Stay upright to keep the work on the triceps."],
 ["Sinking so deep that your shoulders roll forward.", "Flaring the elbows out wide."],
 ["Barki nisko, z dala od uszu.",
  "Opuszczaj się, aż ramiona będą mniej więcej równoległe do podłogi.",
  "Utrzymuj tułów pionowo, żeby pracował triceps."],
 ["Zbyt głębokie zejście, przy którym barki uciekają do przodu.", "Szeroko rozłożone łokcie."])

add("Pushups Push-Up_Wide Push-Ups_-_Close_Triceps_Position Push-Ups_With_Feet_Elevated nt_incline_push_up_on_bench",
 ["Hold a straight line from head to heels with glutes and abs tight.",
  "Hands under your shoulders, elbows about 45° from your body.",
  "Touch your chest to the floor and push it away."],
 ["Hips sagging or piking up.", "Head reaching toward the floor first."],
 ["Utrzymuj prostą linię od głowy do pięt, pośladki i brzuch napięte.",
  "Dłonie pod barkami, łokcie około 45° od tułowia.",
  "Dotknij klatką podłogi i odepchnij ją."],
 ["Opadające albo zadarte biodra.", "Wysuwanie głowy do podłogi przed klatką."])

add("Plank",
 ["Elbows under your shoulders, forearms parallel.",
  "Squeeze your glutes and pull your belly button in.",
  "Breathe steadily; don't hold your breath."],
 ["Hips sagging toward the floor.", "Hips pushed high to make it easier."],
 ["Łokcie pod barkami, przedramiona równolegle.",
  "Napnij pośladki i wciągnij pępek.",
  "Oddychaj spokojnie, nie wstrzymuj powietrza."],
 ["Biodra opadające do podłogi.", "Biodra wypchnięte wysoko, żeby było łatwiej."])

add("Barbell_Hip_Thrust Barbell_Glute_Bridge nt_dumbbell_hip_thrust nt_single_leg_hip_thrust nt_hs_glute_drive",
 ["Rest the bench under your shoulder blades and tuck your chin.",
  "Set your feet so your shins are vertical at the top.",
  "Drive through your heels and squeeze your glutes to lock out."],
 ["Arching the lower back instead of extending the hips.", "Pushing through your toes."],
 ["Oprzyj ławkę pod łopatkami, broda przyciągnięta.",
  "Ustaw stopy tak, żeby na górze piszczele były pionowo.",
  "Wypychaj piętami i napnij pośladki w zablokowaniu."],
 ["Wyginanie lędźwi zamiast prostowania bioder.", "Wypychanie palcami stóp."])

add("Dumbbell_Lunges Barbell_Lunge",
 ["Take a long enough step that your front shin stays close to vertical.",
  "Drop the back knee straight down toward the floor.",
  "Push through the front heel to come back up."],
 ["Front knee caving inward.", "Short steps that push all the load onto the front knee."],
 ["Rób na tyle długi krok, żeby przednia piszczel była prawie pionowo.",
  "Opuszczaj tylne kolano prosto w dół.",
  "Wracaj, odpychając się piętą przedniej nogi."],
 ["Przednie kolano uciekające do środka.", "Krótkie kroki przerzucające cały ciężar na przednie kolano."])

add("Split_Squat_with_Dumbbells Split_Squats nt_bulgarian_split_squat",
 ["Keep most of your weight on the front foot.",
  "Lower straight down until the back knee nearly touches the floor.",
  "A slight forward lean works the glutes more; upright works the quads more."],
 ["Pushing off the back foot.", "Wobbling because your feet are in one line; keep them hip-width apart."],
 ["Większość ciężaru na przedniej nodze.",
  "Opuszczaj się prosto w dół, aż tylne kolano prawie dotknie podłogi.",
  "Lekki skłon do przodu mocniej angażuje pośladki, wyprostowany tułów czworogłowe."],
 ["Odpychanie się tylną nogą.", "Chwianie się przez stopy w jednej linii; trzymaj je na szerokość bioder."])

add("Leg_Extensions nt_hs_select_leg_extension nt_hs_iso_lateral_leg_extension",
 ["Line up your knees with the machine's pivot.",
  "Straighten your legs fully and pause for a moment at the top.",
  "Lower slowly instead of letting the weight drop."],
 ["Kicking the weight up with momentum.", "Lifting your hips off the seat."],
 ["Ustaw kolana w osi obrotu maszyny.",
  "Wyprostuj nogi do końca i zatrzymaj się na chwilę na górze.",
  "Opuszczaj powoli, nie pozwól ciężarowi opaść."],
 ["Wyrzucanie ciężaru z rozpędu.", "Odrywanie bioder od siedziska."])

add("Lying_Leg_Curls nt_hs_select_leg_curl nt_hs_seated_leg_curl nt_hs_select_seated_leg_curl",
 ["Keep your hips pressed down into the pad.",
  "Curl your heels toward your glutes as far as you can.",
  "Lower under control until your legs are almost straight."],
 ["Hips lifting to cheat the weight up.", "Cutting the range short at either end."],
 ["Biodra dociśnięte do oparcia.",
  "Przyciągaj pięty do pośladków tak daleko, jak możesz.",
  "Opuszczaj kontrolowanie, prawie do wyprostu nóg."],
 ["Unoszenie bioder, żeby oszukać ruch.", "Skracanie zakresu na górze albo na dole."])

add("Standing_Calf_Raises Seated_Calf_Raise nt_seated_dumbbell_calf_raise nt_hs_select_standing_calf",
 ["Let your heels drop for a full stretch at the bottom.",
  "Rise as high as you can onto the ball of your foot.",
  "Pause for a second at the top and the bottom."],
 ["Bouncing out of the bottom.", "Bending the knees to push the weight up."],
 ["Na dole opuść pięty do pełnego rozciągnięcia.",
  "Wspinaj się jak najwyżej na przodostopie.",
  "Zatrzymaj się na sekundę na górze i na dole."],
 ["Odbijanie się z dołu.", "Uginanie kolan, żeby pomóc sobie w ruchu."])

add("Barbell_Curl Dumbbell_Bicep_Curl nt_bayesian_cable_curl",
 ["Pin your elbows to your sides.",
  "Curl all the way up, then lower slowly to straight arms.",
  "Keep your wrists straight."],
 ["Swinging the weight with your back.", "Elbows drifting forward at the top."],
 ["Łokcie przy tułowiu.",
  "Uginaj do końca, potem powoli opuszczaj do wyprostu.",
  "Nadgarstki proste."],
 ["Zarzucanie ciężaru plecami.", "Wysuwanie łokci do przodu na górze."])

add("Hammer_Curls",
 ["Palms face each other the whole time.",
  "Elbows stay by your sides; only the forearms move.",
  "Lower under control to straight arms."],
 ["Swinging your torso.", "Rotating the palms up halfway through."],
 ["Dłonie przez cały czas zwrócone do siebie.",
  "Łokcie przy tułowiu, pracują tylko przedramiona.",
  "Opuszczaj kontrolowanie do wyprostu."],
 ["Bujanie tułowiem.", "Obracanie dłoni do góry w połowie ruchu."])

add("Triceps_Pushdown nt_cross_body_cable_triceps_extension",
 ["Keep your elbows tucked at your sides.",
  "Push down until your arms are straight and squeeze.",
  "Let the handle rise only until your forearms pass parallel."],
 ["Elbows flaring or moving forward.", "Leaning over the handle to push with body weight."],
 ["Łokcie przy tułowiu.",
  "Wypychaj w dół do wyprostu rąk i napnij.",
  "Pozwól uchwytowi wrócić tylko trochę ponad poziom przedramion."],
 ["Rozkładanie łokci albo wysuwanie ich do przodu.", "Nachylanie się nad uchwytem i pchanie masą ciała."])

add("EZ-Bar_Skullcrusher Lying_Triceps_Press",
 ["Keep your upper arms still and angled slightly back toward your head.",
  "Lower the bar toward your forehead or just behind it.",
  "Extend your elbows fully without locking hard."],
 ["Elbows flaring out.", "Turning it into a press by moving the upper arms."],
 ["Ramiona nieruchomo, lekko odchylone w stronę głowy.",
  "Opuszczaj drążek do czoła albo tuż za głowę.",
  "Prostuj łokcie do końca, bez gwałtownego blokowania."],
 ["Rozkładanie łokci na boki.", "Zamienianie ruchu w wyciskanie przez ruszanie ramionami."])

add("Side_Lateral_Raise nt_hs_lateral_raise nt_hs_select_lateral_raise nt_cable_y_raise",
 ["Keep a slight bend in your elbows.",
  "Lift out to the sides until your arms are about shoulder height.",
  "Lead with your elbows, not your hands."],
 ["Swinging the weights up with your hips.", "Shrugging so the traps take over."],
 ["Łokcie lekko ugięte.",
  "Unoś ramiona bokiem mniej więcej do wysokości barków.",
  "Prowadź ruch łokciami, nie dłońmi."],
 ["Zarzucanie ciężarów biodrami.", "Unoszenie barków, przez co pracują czworoboczne."])

add("Face_Pull",
 ["Set the rope at about face height.",
  "Pull toward your eyes and spread the rope apart.",
  "Finish with your hands beside your ears, elbows high."],
 ["Pulling to the chest with low elbows, which turns it into a row.", "Leaning back to move more weight."],
 ["Ustaw linkę mniej więcej na wysokości twarzy.",
  "Ciągnij w stronę oczu i rozciągaj końce liny na boki.",
  "Zakończ z dłońmi przy uszach, łokcie wysoko."],
 ["Ciągnięcie do klatki z nisko opuszczonymi łokciami, przez co wychodzi wiosłowanie.", "Odchylanie się, żeby ruszyć większy ciężar."])

add("Hanging_Leg_Raise",
 ["Start from a still hang without swinging.",
  "Curl your pelvis up toward your ribs, not just your legs up.",
  "Lower slowly to keep momentum out of it."],
 ["Swinging to get the legs up.", "Only lifting the legs to 90° without tilting the pelvis."],
 ["Zacznij z nieruchomego zwisu, bez bujania.",
  "Podwijaj miednicę w stronę żeber, nie tylko unoś nogi.",
  "Opuszczaj powoli, żeby nie brać rozpędu."],
 ["Bujanie się, żeby unieść nogi.", "Unoszenie nóg do 90° bez podwinięcia miednicy."])

add("Cable_Crossover Dumbbell_Flyes nt_hs_select_pec_fly nt_hs_super_fly",
 ["Keep a fixed, slight bend in your elbows.",
  "Open wide until you feel a stretch across your chest.",
  "Bring your hands together in a hugging arc."],
 ["Bending and straightening the elbows so it becomes a press.", "Going so deep the shoulders roll forward."],
 ["Łokcie lekko ugięte i w stałym kącie.",
  "Rozchylaj ramiona do odczucia rozciągnięcia klatki.",
  "Łącz dłonie łukiem, jak przy obejmowaniu."],
 ["Uginanie i prostowanie łokci, przez co wychodzi wyciskanie.", "Zbyt głębokie rozciągnięcie, przy którym barki uciekają do przodu."])

# Variants the entries above fit as written.
alias("Barbell_Squat", "Barbell_Full_Squat Wide_Stance_Barbell_Squat Narrow_Stance_Squats Squat_with_Bands Squat_with_Chains Olympic_Squat")
alias("Front_Barbell_Squat", "Front_Squat_Clean_Grip")
alias("Barbell_Deadlift", "Deficit_Deadlift Deadlift_with_Bands Deadlift_with_Chains Axle_Deadlift Reverse_Band_Deadlift")
alias("Sumo_Deadlift", "Sumo_Deadlift_with_Bands Sumo_Deadlift_with_Chains Reverse_Band_Sumo_Deadlift")
alias("Romanian_Deadlift", "Stiff-Legged_Dumbbell_Deadlift Smith_Machine_Stiff-Legged_Deadlift Romanian_Deadlift_from_Deficit")
alias("Barbell_Bench_Press_-_Medium_Grip", "Smith_Machine_Bench_Press Smith_Machine_Incline_Bench_Press Smith_Machine_Close-Grip_Bench_Press "
      "Bench_Press_-_Powerlifting Bench_Press_with_Chains Bench_Press_-_With_Bands Reverse_Band_Bench_Press")
alias("Dumbbell_Bench_Press", "Dumbbell_Bench_Press_with_Neutral_Grip Hammer_Grip_Incline_DB_Bench_Press Incline_Dumbbell_Bench_With_Palms_Facing_In "
      "Decline_Dumbbell_Bench_Press")
alias("Dumbbell_Shoulder_Press", "Barbell_Shoulder_Press Seated_Barbell_Military_Press Seated_Dumbbell_Press Leverage_Shoulder_Press "
      "Smith_Machine_Overhead_Shoulder_Press Seated_Cable_Shoulder_Press nt_hs_mts_shoulder_press nt_hs_select_shoulder_press")
alias("Dumbbell_Flyes", "Flat_Bench_Cable_Flyes Incline_Cable_Flye Incline_Dumbbell_Flyes Decline_Dumbbell_Flyes One-Arm_Flat_Bench_Dumbbell_Flye "
      "Low_Cable_Crossover Single-Arm_Cable_Crossover Cross_Over_-_With_Bands Butterfly")
alias("Barbell_Curl", "EZ-Bar_Curl Close-Grip_EZ_Bar_Curl Close-Grip_EZ-Bar_Curl_with_Band Close-Grip_Standing_Barbell_Curl Wide-Grip_Standing_Barbell_Curl "
      "Dumbbell_Alternate_Bicep_Curl Seated_Dumbbell_Curl Seated_Dumbbell_Inner_Biceps_Curl Standing_Inner-Biceps_Curl "
      "Standing_Biceps_Cable_Curl Standing_One-Arm_Cable_Curl Incline_Dumbbell_Curl Alternate_Incline_Dumbbell_Curl Incline_Inner_Biceps_Curl")
alias("Hammer_Curls", "Alternate_Hammer_Curl Cable_Hammer_Curls_-_Rope_Attachment Incline_Hammer_Curls")
alias("Triceps_Pushdown", "Triceps_Pushdown_-_Rope_Attachment Triceps_Pushdown_-_V-Bar_Attachment Reverse_Grip_Triceps_Pushdown")
alias("EZ-Bar_Skullcrusher", "Decline_EZ_Bar_Triceps_Extension Incline_Barbell_Triceps_Extension Lying_Close-Grip_Barbell_Triceps_Extension_Behind_The_Head "
      "Cable_Lying_Triceps_Extension Band_Skull_Crusher")
alias("Dips_-_Triceps_Version", "Parallel_Bar_Dip Ring_Dips Dip_Machine")
alias("Side_Lateral_Raise", "Seated_Side_Lateral_Raise Cable_Seated_Lateral_Raise Lateral_Raise_-_With_Bands One-Arm_Side_Laterals Standing_Low-Pulley_Deltoid_Raise")
alias("Wide-Grip_Lat_Pulldown", "Close-Grip_Front_Lat_Pulldown V-Bar_Pulldown Underhand_Cable_Pulldowns Full_Range-Of-Motion_Lat_Pulldown "
      "nt_hs_mts_front_pulldown nt_hs_iso_lateral_wide_pulldown")
alias("Pullups", "Weighted_Pull_Ups V-Bar_Pullup Mixed_Grip_Chin Band_Assisted_Pull-Up nt_hs_select_assisted_chin_up")
alias("Seated_Cable_Rows", "Seated_One-arm_Cable_Pulley_Rows Elevated_Cable_Rows")
alias("Bent_Over_Barbell_Row", "Reverse_Grip_Bent-Over_Rows Smith_Machine_Bent_Over_Row Bent_Over_Two-Arm_Long_Bar_Row T-Bar_Row_with_Handle")
alias("One-Arm_Dumbbell_Row", "One-Arm_Kettlebell_Row Bent_Over_One-Arm_Long_Bar_Row One-Arm_Long_Bar_Row")
alias("Leg_Press", "nt_hs_linear_leg_press nt_hs_iso_lateral_leg_press nt_hs_select_seated_leg_press")
alias("Hack_Squat", "nt_hs_linear_hack_squat nt_hs_v_squat nt_hs_pendulum_x_squat nt_hs_super_squat_press Lying_Machine_Squat")
alias("Lying_Leg_Curls", "Seated_Leg_Curl nt_hs_iso_lateral_leg_curl")
alias("Leg_Extensions", "Single-Leg_Leg_Extension nt_hs_mts_leg_extension")
alias("Standing_Calf_Raises", "Standing_Barbell_Calf_Raise Standing_Dumbbell_Calf_Raise Smith_Machine_Calf_Raise Calf_Raise_On_A_Dumbbell Donkey_Calf_Raises "
      "Barbell_Seated_Calf_Raise Dumbbell_Seated_One-Leg_Calf_Raise Calf_Press Calf_Press_On_The_Leg_Press_Machine "
      "nt_hs_seated_calf_raise nt_hs_select_horizontal_calf nt_hs_super_horizontal_calf")
alias("Barbell_Lunge", "Barbell_Walking_Lunge Bodyweight_Walking_Lunge Dumbbell_Rear_Lunge Elevated_Back_Lunge")
alias("Split_Squats", "Smith_Single-Leg_Split_Squat")
alias("Pushups", "Incline_Push-Up Incline_Push-Up_Medium Incline_Push-Up_Wide Incline_Push-Up_Close-Grip Decline_Push-Up")
alias("Hanging_Leg_Raise", "Knee_Hip_Raise_On_Parallel_Bars")
alias("Face_Pull", "Cable_Rope_Rear-Delt_Rows")

# New families.
add("Machine_Bench_Press Leverage_Incline_Chest_Press Leverage_Decline_Chest_Press nt_hs_mts_chest_press nt_hs_mts_incline_press nt_hs_mts_decline_press "
    "nt_hs_select_chest_press nt_hs_iso_lateral_horizontal_bench_press nt_hs_iso_lateral_super_incline_press nt_hs_iso_lateral_decline_press "
    "nt_hs_iso_lateral_wide_chest nt_hs_iso_lateral_chest_press_chest_back nt_hs_gb_incline_press nt_hs_gb_combo_decline",
 ["Set the seat so the handles line up with the middle of your chest.",
  "Keep your shoulder blades back and against the pad.",
  "Press out without slamming the elbows straight, then come back until you feel the stretch."],
 ["Shoulders rolling forward off the pad at the end of each rep.", "Letting the weight crash down between reps."],
 ["Ustaw siedzisko tak, żeby uchwyty były na wysokości środka klatki.",
  "Łopatki ściągnięte i oparte o oparcie.",
  "Wypychaj bez gwałtownego blokowania łokci i wracaj do odczucia rozciągnięcia."],
 ["Barki odrywające się od oparcia na końcu powtórzenia.", "Upuszczanie ciężaru między powtórzeniami."])

add("Decline_Barbell_Bench_Press Smith_Machine_Decline_Press Wide-Grip_Decline_Barbell_Bench_Press",
 ["Hook your legs in firmly before you unrack.",
  "Pull your shoulder blades back and down.",
  "Lower the bar to the bottom of your chest and press it back over your shoulders."],
 ["Bouncing the bar off your chest.", "Lowering toward the neck instead of the lower chest."],
 ["Przed zdjęciem sztangi mocno zaczep nogi.",
  "Ściągnij łopatki do tyłu i w dół.",
  "Opuszczaj sztangę na dół klatki i wyciskaj z powrotem nad barki."],
 ["Odbijanie sztangi od klatki.", "Opuszczanie w stronę szyi zamiast na dół klatki."])

add("Floor_Press Dumbbell_Floor_Press Alternating_Floor_Press One_Arm_Floor_Press Floor_Press_with_Chains",
 ["Lie with your knees bent and your shoulder blades pulled together.",
  "Lower until your upper arms touch the floor, elbows about 45° from your body.",
  "Pause on the floor for a moment, then press up without bouncing."],
 ["Slamming the elbows into the floor.", "Flaring the elbows straight out to the sides."],
 ["Połóż się z ugiętymi kolanami i ściągniętymi łopatkami.",
  "Opuszczaj, aż ramiona dotkną podłogi, łokcie ok. 45° od tułowia.",
  "Zatrzymaj się chwilę na podłodze i wyciśnij bez odbicia."],
 ["Uderzanie łokciami o podłogę.", "Rozkładanie łokci prosto na boki."])

add("Dips_-_Chest_Version",
 ["Lean your torso forward and let your elbows drift out a little.",
  "Lower until you feel a stretch across the chest, around shoulder level with the bars.",
  "Keep your shoulders down, away from your ears."],
 ["Dropping so deep the shoulders roll forward.", "Swinging the legs to get back up."],
 ["Pochyl tułów do przodu i pozwól łokciom lekko odejść na boki.",
  "Opuszczaj do odczucia rozciągnięcia klatki, mniej więcej do poziomu poręczy.",
  "Barki nisko, z dala od uszu."],
 ["Schodzenie tak nisko, że barki uciekają do przodu.", "Bujanie nogami, żeby wrócić do góry."])

add("Bench_Dips Weighted_Bench_Dip",
 ["Keep your back close to the bench.",
  "Bend the elbows straight back, not out to the sides.",
  "Stop when your upper arms are about parallel to the floor."],
 ["Sinking too low, which strains the front of the shoulder.", "Pushing with the legs instead of the arms."],
 ["Plecy blisko ławki.",
  "Zginaj łokcie prosto do tyłu, nie na boki.",
  "Zatrzymaj się, gdy ramiona są mniej więcej równoległe do podłogi."],
 ["Schodzenie za nisko, co przeciąża przód barku.", "Pchanie nogami zamiast rękami."])

add("Standing_Dumbbell_Press Standing_Palms-In_Dumbbell_Press Standing_Alternating_Dumbbell_Press Dumbbell_One-Arm_Shoulder_Press "
    "Standing_Palm-In_One-Arm_Dumbbell_Press Two-Arm_Kettlebell_Military_Press Alternating_Kettlebell_Press Shoulder_Press_-_With_Bands "
    "Cable_Shoulder_Press Alternating_Cable_Shoulder_Press",
 ["Squeeze your glutes and brace so your lower back stays flat.",
  "Start with your hands at shoulder height, forearms vertical.",
  "Press straight up until your arms are straight beside your ears."],
 ["Leaning back to turn it into an incline press.", "Pressing forward instead of straight up."],
 ["Napnij pośladki i brzuch, żeby odcinek lędźwiowy się nie wyginał.",
  "Zacznij z dłońmi na wysokości barków, przedramiona pionowo.",
  "Wyciskaj prosto w górę, aż ręce będą wyprostowane przy uszach."],
 ["Odchylanie się do tyłu, przez co wychodzi wyciskanie skośne.", "Wyciskanie do przodu zamiast prosto w górę."])

add("Push_Press Double_Kettlebell_Push_Press One-Arm_Kettlebell_Push_Press",
 ["Dip a few centimetres by bending the knees, keeping your torso upright.",
  "Drive up hard with the legs and let that start the press.",
  "Finish with straight arms overhead, weight over the middle of your foot."],
 ["Dipping too deep, so it becomes a squat.", "Letting the chest fall forward in the dip."],
 ["Ugnij kolana o kilka centymetrów, tułów pionowo.",
  "Mocno wybij się nogami i od tego zacznij wyciskanie.",
  "Zakończ z prostymi rękami nad głową, ciężar nad środkiem stopy."],
 ["Zbyt głębokie ugięcie, które zamienia ruch w przysiad.", "Opadanie klatki do przodu podczas ugięcia."])

add("nt_half_kneeling_landmine_press",
 ["Kneel on the same side as the pressing hand and squeeze that glute.",
  "Press up and forward along the bar's path, letting your shoulder blade move.",
  "Keep your ribs down and don't twist."],
 ["Leaning back to press.", "Twisting the hips to push the bar."],
 ["Klęknij na kolano po stronie ręki, która wyciska, i napnij ten pośladek.",
  "Wyciskaj w górę i do przodu po torze sztangi, pozwalając łopatce pracować.",
  "Żebra w dół, bez skrętu tułowia."],
 ["Odchylanie się do tyłu.", "Skręcanie bioder, żeby dopchnąć sztangę."])

add("Front_Dumbbell_Raise Front_Cable_Raise Front_Plate_Raise Front_Two-Dumbbell_Raise Single_Dumbbell_Raise Front_Incline_Dumbbell_Raise",
 ["Keep a slight bend in your elbows.",
  "Raise to shoulder height in front of you.",
  "Lower slowly without swinging."],
 ["Leaning back and using the hips to lift.", "Going much higher than shoulder height."],
 ["Łokcie lekko ugięte.",
  "Unoś przed sobą do wysokości barków.",
  "Opuszczaj powoli, bez bujania."],
 ["Odchylanie się i pomaganie sobie biodrami.", "Unoszenie dużo powyżej barków."])

add("Reverse_Flyes Reverse_Machine_Flyes Cable_Rear_Delt_Fly Seated_Bent-Over_Rear_Delt_Raise Dumbbell_Lying_Rear_Lateral_Raise "
    "Dumbbell_Lying_One-Arm_Rear_Lateral_Raise Bent_Over_Dumbbell_Rear_Delt_Raise_With_Head_On_Bench Lying_Rear_Delt_Raise "
    "nt_hs_lying_rear_delt_fly nt_hs_select_rear_delt Back_Flyes_-_With_Bands Bent_Over_Low-Pulley_Side_Lateral",
 ["Keep a fixed, slight bend in your elbows.",
  "Sweep your arms out and back, leading with the elbows.",
  "Stop when your arms are in line with your body, without pinching the shoulder blades hard."],
 ["Shrugging so the traps take over.", "Bending the elbows so it turns into a row."],
 ["Łokcie lekko ugięte i w stałym kącie.",
  "Prowadź ręce na boki i do tyłu, łokciami przodem.",
  "Zatrzymaj, gdy ręce są w linii z tułowiem, bez mocnego ściągania łopatek."],
 ["Unoszenie barków, przez co przejmują kaptury.", "Uginanie łokci, przez co wychodzi wiosłowanie."])

add("Band_Pull_Apart",
 ["Hold the band at shoulder height with straight arms.",
  "Pull it apart until it touches your chest.",
  "Return slowly and keep tension on the band."],
 ["Shrugging the shoulders up.", "Arching the back to finish the rep."],
 ["Trzymaj gumę na wysokości barków, ręce proste.",
  "Rozciągaj ją, aż dotknie klatki.",
  "Wracaj powoli, nie puszczając napięcia."],
 ["Unoszenie barków.", "Wyginanie pleców, żeby dokończyć powtórzenie."])

add("Barbell_Shrug Dumbbell_Shrug Cable_Shrugs Leverage_Shrug nt_hs_shrug Barbell_Shrug_Behind_The_Back Smith_Machine_Behind_the_Back_Shrug "
    "Calf-Machine_Shoulder_Shrug",
 ["Stand tall with straight arms.",
  "Lift your shoulders straight up toward your ears.",
  "Hold the top for a second, then lower all the way."],
 ["Rolling the shoulders in circles.", "Bending the elbows to pull the weight."],
 ["Stań prosto, ręce wyprostowane.",
  "Unoś barki prosto w górę, w stronę uszu.",
  "Przytrzymaj sekundę na górze i opuść do końca."],
 ["Krążenie barkami.", "Uginanie łokci, żeby podciągnąć ciężar."])

add("Upright_Barbell_Row Standing_Dumbbell_Upright_Row Upright_Cable_Row Smith_Machine_Upright_Row Upright_Row_-_With_Bands "
    "Dumbbell_One-Arm_Upright_Row Smith_Machine_One-Arm_Upright_Row",
 ["Use a grip at least shoulder-width.",
  "Lead with your elbows and keep the weight close to your body.",
  "Stop around chest height, with elbows no higher than your shoulders."],
 ["Pulling up to the chin with a narrow grip, which pinches the shoulders.", "Swinging the weight up with the hips."],
 ["Chwyt co najmniej na szerokość barków.",
  "Prowadź ruch łokciami, ciężar blisko ciała.",
  "Zatrzymaj na wysokości klatki, łokcie nie wyżej niż barki."],
 ["Ciągnięcie wąskim chwytem pod brodę, co ściska staw barkowy.", "Podrzucanie ciężaru biodrami."])

add("Reverse_Barbell_Curl Reverse_Cable_Curl Standing_Dumbbell_Reverse_Curl Reverse_Plate_Curls",
 ["Grip with your palms facing down.",
  "Keep your wrists straight, in line with your forearms.",
  "Elbows stay by your sides; only the forearms move."],
 ["Letting the wrists bend back under the weight.", "Swinging the torso."],
 ["Chwyt nachwytem, dłonie skierowane w dół.",
  "Nadgarstki proste, w linii z przedramionami.",
  "Łokcie przy tułowiu, pracują tylko przedramiona."],
 ["Nadgarstki odginające się pod ciężarem.", "Bujanie tułowiem."])

add("Preacher_Curl Cable_Preacher_Curl Machine_Preacher_Curls One_Arm_Dumbbell_Preacher_Curl Two-Arm_Dumbbell_Preacher_Curl "
    "Preacher_Hammer_Dumbbell_Curl Reverse_Barbell_Preacher_Curls Machine_Bicep_Curl nt_hs_mts_biceps_curl nt_hs_select_biceps_curl "
    "nt_hs_seated_biceps Concentration_Curls Standing_Concentration_Curl Standing_One-Arm_Dumbbell_Curl_Over_Incline_Bench",
 ["Keep the back of your upper arm pressed into the pad or your thigh.",
  "Lower until your arm is almost straight, slowly.",
  "Curl up and squeeze without lifting the elbow."],
 ["Dropping fast into the bottom, where the elbow is most exposed.", "Lifting the elbow or shoulder off the support."],
 ["Tył ramienia dociśnięty do podpórki lub uda.",
  "Opuszczaj powoli, aż ręka będzie prawie prosta.",
  "Uginaj i napnij na górze, nie odrywając łokcia."],
 ["Szybkie opadanie na dół, gdzie łokieć jest najbardziej obciążony.", "Odrywanie łokcia lub barku od podpórki."])

add("Cable_Rope_Overhead_Triceps_Extension Triceps_Overhead_Extension_with_Rope Standing_Dumbbell_Triceps_Extension "
    "Standing_One-Arm_Dumbbell_Triceps_Extension Standing_Overhead_Barbell_Triceps_Extension Seated_Triceps_Press "
    "Kettlebell_Overhead_Triceps_Extension Dumbbell_One-Arm_Triceps_Extension Speed_Band_Overhead_Triceps",
 ["Keep your elbows pointing forward and close to your head.",
  "Lower behind your head until you feel a deep stretch.",
  "Keep your ribs down so your back doesn't arch."],
 ["Elbows flaring out wide.", "Arching the lower back to push the weight up."],
 ["Łokcie skierowane do przodu i blisko głowy.",
  "Opuszczaj za głowę do mocnego rozciągnięcia.",
  "Żebra w dół, żeby plecy się nie wyginały."],
 ["Łokcie uciekające szeroko na boki.", "Wyginanie lędźwi, żeby wypchnąć ciężar."])

add("Tricep_Dumbbell_Kickback Standing_Bent-Over_One-Arm_Dumbbell_Triceps_Extension Standing_Bent-Over_Two-Arm_Dumbbell_Triceps_Extension "
    "Seated_Bent-Over_One-Arm_Dumbbell_Triceps_Extension Seated_Bent-Over_Two-Arm_Dumbbell_Triceps_Extension",
 ["Lift your upper arm in line with your torso and keep it there.",
  "Straighten the arm fully and pause.",
  "Lower only until the forearm hangs straight down."],
 ["The upper arm dropping as you tire.", "Swinging the weight up."],
 ["Unieś ramię w linii z tułowiem i trzymaj je w miejscu.",
  "Wyprostuj rękę do końca i zatrzymaj.",
  "Opuszczaj tylko do przedramienia zwisającego pionowo."],
 ["Opadanie ramienia wraz ze zmęczeniem.", "Wymachiwanie ciężarem."])

add("Machine_Triceps_Extension nt_hs_mts_triceps_extension nt_hs_select_triceps_extension",
 ["Set the seat so your elbows line up with the machine's pivot.",
  "Keep your elbows on the pad the whole time.",
  "Straighten fully, then let the handles come back slowly."],
 ["Elbows lifting off the pad.", "Leaning into the handles to push with body weight."],
 ["Ustaw siedzisko tak, żeby łokcie były w osi obrotu maszyny.",
  "Łokcie cały czas na podpórce.",
  "Wyprostuj ręce do końca i powoli wracaj."],
 ["Odrywanie łokci od podpórki.", "Napieranie na uchwyty ciężarem ciała."])

add("nt_hs_mts_row nt_hs_iso_lateral_row nt_hs_iso_lateral_low_row nt_hs_iso_lateral_high_row nt_hs_mts_high_row nt_hs_iso_lateral_dy_row "
    "nt_hs_iso_lateral_row_chest_back nt_hs_iso_lateral_t_bar_row nt_hs_gb_high_row Leverage_Iso_Row Leverage_High_Row "
    "Lying_T-Bar_Row Lying_Cambered_Barbell_Row Incline_Bench_Pull Dumbbell_Incline_Row",
 ["Set the pad so you reach the handles with straight arms.",
  "Keep your chest on the pad the whole time.",
  "Drive your elbows back and squeeze your shoulder blades, then let them spread on the way back."],
 ["Chest peeling off the pad to heave the weight.", "Shrugging your shoulders up."],
 ["Ustaw podparcie tak, żeby sięgać uchwytów prostymi rękami.",
  "Klatka cały czas oparta o podparcie.",
  "Prowadź łokcie do tyłu i ściągnij łopatki, a przy powrocie pozwól im się rozsunąć."],
 ["Odrywanie klatki od podparcia, żeby szarpnąć ciężar.", "Unoszenie barków."])

add("Inverted_Row Inverted_Row_with_Straps Suspended_Row Bodyweight_Mid_Row",
 ["Keep a straight line from head to heels.",
  "Pull your chest to the bar or handles, elbows about 45° from your body.",
  "Lower until your arms are straight."],
 ["Hips sagging.", "Reaching with the chin instead of pulling the chest up."],
 ["Ciało w jednej linii od głowy do pięt.",
  "Przyciągaj klatkę do drążka lub uchwytów, łokcie ok. 45° od tułowia.",
  "Opuszczaj do wyprostu rąk."],
 ["Opadające biodra.", "Wyciąganie brody zamiast przyciągania klatki."])

add("Straight-Arm_Pulldown Rope_Straight-Arm_Pulldown",
 ["Hinge forward slightly with a fixed, slight bend in your elbows.",
  "Sweep the bar down to your thighs by moving only at the shoulders.",
  "Let it rise until you feel your lats stretch."],
 ["Bending the elbows so it turns into a pushdown.", "Rocking the torso to move the weight."],
 ["Lekko pochyl się, łokcie lekko ugięte i nieruchome.",
  "Prowadź drążek łukiem do ud, ruch tylko w barkach.",
  "Pozwól mu wrócić do odczucia rozciągnięcia najszerszych."],
 ["Uginanie łokci, przez co wychodzi prostowanie ramion.", "Bujanie tułowiem."])

add("Bent-Arm_Dumbbell_Pullover Straight-Arm_Dumbbell_Pullover nt_hs_pullover",
 ["Keep your ribs down and your hips steady.",
  "Lower the weight behind your head in an arc until your lats and chest stretch.",
  "Pull it back over your chest without bending the elbows more."],
 ["Arching the back to reach deeper.", "Bending the elbows so it turns into a triceps extension."],
 ["Żebra w dół, biodra nieruchome.",
  "Opuszczaj ciężar łukiem za głowę do rozciągnięcia najszerszych i klatki.",
  "Wracaj nad klatkę bez dodatkowego uginania łokci."],
 ["Wyginanie pleców, żeby zejść głębiej.", "Uginanie łokci, przez co wychodzi prostowanie ramion."])

add("Trap_Bar_Deadlift",
 ["Stand in the centre of the bar and grip the middle of the handles.",
  "Brace, pull the slack out, then push the floor away.",
  "Stand tall at the top without leaning back."],
 ["Rounding the lower back at the start.", "Hips shooting up before the bar moves."],
 ["Stań na środku sztangi i chwyć środek uchwytów.",
  "Napnij brzuch, wybierz luz i odepchnij podłogę.",
  "Na górze stań prosto, bez odchylania się."],
 ["Zaokrąglanie lędźwi na starcie.", "Biodra uciekające w górę, zanim ruszy sztanga."])

add("Good_Morning Good_Morning_off_Pins Stiff_Leg_Barbell_Good_Morning Band_Good_Morning",
 ["Soft knees, then push your hips back.",
  "Keep your back flat as your torso tips forward.",
  "Stop when your hamstrings are stretched, and drive the hips forward to stand."],
 ["Rounding the back to go lower.", "Bending the knees so it turns into a squat."],
 ["Kolana lekko ugięte, biodra wypychane do tyłu.",
  "Plecy proste, gdy tułów pochyla się do przodu.",
  "Zatrzymaj przy rozciągnięciu tylnej części uda i wróć, wypychając biodra."],
 ["Zaokrąglanie pleców, żeby zejść niżej.", "Uginanie kolan, przez co wychodzi przysiad."])

add("Hyperextensions_Back_Extensions nt_hs_select_back_extension Weighted_Ball_Hyperextension",
 ["Set the pad just below your hip bones.",
  "Hinge down with a flat back until you feel a stretch.",
  "Rise until your body is in a straight line, not past it."],
 ["Swinging up with momentum.", "Arching far past straight at the top."],
 ["Ustaw podparcie tuż pod kośćmi biodrowymi.",
  "Pochylaj się z prostymi plecami do odczucia rozciągnięcia.",
  "Wracaj do linii prostej, nie dalej."],
 ["Wybijanie się z rozpędu.", "Mocne przeprostowanie na górze."])

add("Glute_Ham_Raise nt_hs_glute_ham_raise Natural_Glute_Ham_Raise Floor_Glute-Ham_Raise nt_assisted_nordic_curl",
 ["Keep your hips straight so knees, hips and shoulders stay in one line.",
  "Lower forward as slowly as you can control.",
  "Pull yourself back up with your hamstrings, using your hands if needed."],
 ["Bending at the hips to make it easier.", "Dropping the last part without control."],
 ["Biodra wyprostowane, kolana, biodra i barki w jednej linii.",
  "Opuszczaj się do przodu tak wolno, jak potrafisz.",
  "Wracaj siłą tylnej części uda, w razie potrzeby pomagając sobie rękami."],
 ["Zginanie w biodrach, żeby było łatwiej.", "Niekontrolowane opadanie na końcu."])

add("nt_reverse_nordic_curl",
 ["Kneel tall with your hips straight and glutes squeezed.",
  "Lean back slowly as far as you can while keeping the hips straight.",
  "Pull yourself back up with your quads."],
 ["Bending at the hips as you lean back.", "Going further than you can control."],
 ["Klęknij prosto, biodra wyprostowane, pośladki napięte.",
  "Powoli odchylaj się do tyłu tak daleko, jak utrzymasz proste biodra.",
  "Wracaj siłą mięśni czworogłowych."],
 ["Zginanie bioder przy odchylaniu.", "Schodzenie dalej, niż jesteś w stanie kontrolować."])

add("Butt_Lift_Bridge Single_Leg_Glute_Bridge Hip_Lift_with_Band",
 ["Lie on your back with your heels close to your glutes.",
  "Drive through your heels and lift your hips until your body is straight from knees to shoulders.",
  "Squeeze your glutes at the top for a second."],
 ["Arching the lower back instead of extending the hips.", "Pushing through your toes."],
 ["Połóż się na plecach, pięty blisko pośladków.",
  "Wypchnij biodra, pchając piętami, do linii prostej od kolan do barków.",
  "Na górze napnij pośladki na sekundę."],
 ["Wyginanie lędźwi zamiast wyprostu w biodrach.", "Pchanie palcami stóp."])

add("nt_cable_glute_kickback One-Legged_Cable_Kickback Glute_Kickback nt_hs_select_hip_glute",
 ["Brace your core and keep your hips square.",
  "Push your heel back by squeezing the glute.",
  "Stop before your lower back starts to arch."],
 ["Arching the back to kick higher.", "Swinging the leg with momentum."],
 ["Napnij brzuch, biodra ustawione równo.",
  "Wypychaj piętę do tyłu, napinając pośladek.",
  "Zatrzymaj, zanim lędźwie zaczną się wyginać."],
 ["Wyginanie pleców, żeby wyrzucić nogę wyżej.", "Wymachiwanie nogą."])

add("Thigh_Abductor nt_hs_select_hip_abduction",
 ["Sit back against the pad with your hips all the way back.",
  "Push your knees apart as far as you can.",
  "Return slowly without letting the plates touch."],
 ["Leaning forward and rocking to move the weight.", "Letting the weight snap your legs back together."],
 ["Usiądź, dociskając biodra do oparcia.",
  "Rozsuwaj kolana jak najszerzej.",
  "Wracaj powoli, nie dając płytom się zetknąć."],
 ["Pochylanie się i bujanie, żeby ruszyć ciężar.", "Pozwalanie, by ciężar gwałtownie złączył nogi."])

add("Thigh_Adductor nt_hs_select_hip_adduction",
 ["Start with your legs only as wide as feels comfortable.",
  "Squeeze your knees together and pause.",
  "Return slowly to the stretch."],
 ["Setting the start so wide it strains the groin.", "Bouncing out of the stretch."],
 ["Zacznij z nogami rozsuniętymi tylko na tyle, na ile jest komfortowo.",
  "Ściśnij kolana razem i zatrzymaj.",
  "Wracaj powoli do rozciągnięcia."],
 ["Ustawienie zbyt szerokiego startu, które przeciąża pachwinę.", "Odbijanie się z rozciągnięcia."])

add("nt_standing_cable_hip_abduction",
 ["Stand tall and hold on for balance.",
  "Lift the leg out to the side, leading with the heel.",
  "Keep your torso still instead of leaning away."],
 ["Leaning the torso to lift the leg higher.", "Turning the toes up so the hip flexors take over."],
 ["Stań prosto i przytrzymaj się dla równowagi.",
  "Odwodź nogę w bok, prowadząc piętą.",
  "Tułów nieruchomy, bez odchylania."],
 ["Przechylanie tułowia, żeby unieść nogę wyżej.", "Kierowanie palców w górę, przez co pracują zginacze biodra."])

add("Barbell_Step_Ups Dumbbell_Step_Ups",
 ["Put your whole foot on the box.",
  "Push through the front heel to stand up, not off the back foot.",
  "Lower yourself slowly."],
 ["Pushing off the floor with the back leg.", "The front knee caving in."],
 ["Postaw na skrzyni całą stopę.",
  "Wstawaj, pchając piętą przedniej nogi, a nie odbijając się tylną.",
  "Schodź powoli."],
 ["Odbijanie się od podłogi tylną nogą.", "Kolano przedniej nogi uciekające do środka."])

add("Box_Squat Box_Squat_with_Bands Box_Squat_with_Chains Speed_Box_Squat Barbell_Squat_To_A_Bench Dumbbell_Squat_To_A_Bench "
    "Front_Barbell_Squat_To_A_Bench",
 ["Sit back to the box with your shins close to vertical.",
  "Touch the box under control and stay braced; don't relax onto it.",
  "Drive up through your whole foot."],
 ["Dropping onto the box.", "Rocking back on the box to get momentum."],
 ["Siadaj do tyłu na skrzynię, piszczele blisko pionu.",
  "Dotknij skrzyni pod kontrolą i nie rozluźniaj brzucha.",
  "Wstawaj, pchając całą stopą."],
 ["Opadanie na skrzynię.", "Bujanie się na skrzyni dla rozpędu."])

add("Bodyweight_Squat Chair_Squat",
 ["Feet about shoulder-width, toes turned out a little.",
  "Sit down between your heels with your chest up.",
  "Knees follow your toes; heels stay down."],
 ["Knees caving in.", "Heels lifting off the floor."],
 ["Stopy mniej więcej na szerokość barków, palce lekko na zewnątrz.",
  "Siadaj między pięty z uniesioną klatką.",
  "Kolana w linii palców, pięty na podłodze."],
 ["Kolana uciekające do środka.", "Odrywanie pięt od podłogi."])

add("Crunches Weighted_Crunches Decline_Crunch Exercise_Ball_Crunch Cable_Crunch nt_cable_crunch_kneeling Standing_Rope_Crunch "
    "Cable_Seated_Crunch Ab_Crunch_Machine nt_hs_mts_ab_crunch nt_hs_select_ab_crunch",
 ["Curl your ribs toward your pelvis by rounding your spine.",
  "Breathe out hard as you crunch.",
  "Come back slowly and keep tension."],
 ["Pulling on your neck or arms.", "Bending at the hips instead of rounding the spine."],
 ["Zbliżaj żebra do miednicy, zaokrąglając kręgosłup.",
  "Mocno wydychaj podczas spięcia.",
  "Wracaj powoli, bez utraty napięcia."],
 ["Ciągnięcie za szyję lub rękami.", "Zginanie w biodrach zamiast zaokrąglania kręgosłupa."])

add("Reverse_Crunch Cable_Reverse_Crunch Decline_Reverse_Crunch",
 ["Curl your pelvis up toward your ribs, lifting your hips off the bench or floor.",
  "Keep your knees bent at a fixed angle.",
  "Lower slowly until your hips touch down."],
 ["Swinging the legs to throw the hips up.", "Lower back arching as the legs come down."],
 ["Podwijaj miednicę w stronę żeber, odrywając biodra od ławki lub podłogi.",
  "Kolana ugięte pod stałym kątem.",
  "Opuszczaj powoli, aż biodra dotkną podłoża."],
 ["Wymachiwanie nogami, żeby podrzucić biodra.", "Wyginanie lędźwi przy opuszczaniu nóg."])

add("Russian_Twist Cable_Russian_Twists Seated_Barbell_Twist Plate_Twist",
 ["Sit tall with a straight back.",
  "Turn your ribs and shoulders, not just your arms.",
  "Move slowly and pause at each side."],
 ["Only swinging the arms side to side.", "Rounding the back."],
 ["Siedź prosto, plecy wyprostowane.",
  "Obracaj żebra i barki, nie tylko ręce.",
  "Ruszaj się powoli z pauzą po każdej stronie."],
 ["Machanie samymi rękami na boki.", "Zaokrąglanie pleców."])

add("Side_Bridge nt_side_plank_hip_lift",
 ["Elbow under your shoulder, body in a straight line.",
  "Push the floor away so your shoulder doesn't sink.",
  "Keep your hips stacked and lifted."],
 ["Hips sagging toward the floor.", "Rolling the chest toward the floor."],
 ["Łokieć pod barkiem, ciało w jednej linii.",
  "Odpychaj podłogę, żeby bark się nie zapadał.",
  "Biodra ustawione jedno nad drugim i uniesione."],
 ["Biodra opadające do podłogi.", "Obracanie klatki w stronę podłogi."])

add("nt_copenhagen_side_plank",
 ["Rest the top leg on the bench at the knee or ankle, elbow under your shoulder.",
  "Squeeze the top leg down into the bench to lift your hips.",
  "Hold a straight line from head to feet."],
 ["Hips sagging.", "Starting with the bench at the ankle before you can hold it at the knee."],
 ["Połóż górną nogę na ławce kolanem lub kostką, łokieć pod barkiem.",
  "Dociskaj górną nogę do ławki, unosząc biodra.",
  "Utrzymaj linię prostą od głowy do stóp."],
 ["Opadające biodra.", "Zaczynanie od oparcia kostką, zanim utrzymasz oparcie kolanem."])

add("Pallof_Press",
 ["Stand side-on to the cable with feet shoulder-width and knees soft.",
  "Press the handle straight out from your chest and hold.",
  "Don't let the cable rotate you."],
 ["Twisting toward the cable.", "Leaning away from it."],
 ["Stań bokiem do wyciągu, stopy na szerokość barków, kolana miękkie.",
  "Wypchnij uchwyt prosto od klatki i przytrzymaj.",
  "Nie pozwól, żeby linka cię obracała."],
 ["Skręcanie się w stronę wyciągu.", "Odchylanie się od niego."])

add("Standing_Cable_Wood_Chop Standing_Cable_Lift",
 ["Keep your arms long and let the turn come from your hips and ribs.",
  "Pivot the back foot as you rotate.",
  "Control the return."],
 ["Pulling only with the arms.", "Rounding the back."],
 ["Ręce długie, obrót wychodzi z bioder i żeber.",
  "Obracaj tylną stopę razem z ruchem.",
  "Kontroluj powrót."],
 ["Ciągnięcie samymi rękami.", "Zaokrąglanie pleców."])

add("Dead_Bug",
 ["Press your lower back into the floor and keep it there.",
  "Lower the opposite arm and leg slowly.",
  "Breathe out as you reach."],
 ["Lower back lifting off the floor.", "Rushing the reps."],
 ["Dociśnij lędźwie do podłogi i trzymaj.",
  "Powoli opuszczaj przeciwną rękę i nogę.",
  "Wydychaj przy wyciąganiu."],
 ["Odrywanie lędźwi od podłogi.", "Zbyt szybkie powtórzenia."])

add("Ab_Roller Barbell_Ab_Rollout Barbell_Ab_Rollout_-_On_Knees Suspended_Fallout",
 ["Tuck your pelvis and squeeze your glutes before you roll.",
  "Roll out only as far as you can keep your back from sagging.",
  "Pull back with your abs, not your hips."],
 ["Lower back sagging at the far end.", "Piking the hips up to come back."],
 ["Podwiń miednicę i napnij pośladki przed ruchem.",
  "Wyjeżdżaj tylko tak daleko, jak utrzymasz plecy bez zapadania.",
  "Wracaj brzuchem, nie biodrami."],
 ["Zapadanie lędźwi w najdalszym punkcie.", "Wypychanie bioder w górę przy powrocie."])

add("Sit-Up",
 ["Anchor your feet and keep your chin tucked.",
  "Roll up one vertebra at a time.",
  "Lower slowly."],
 ["Yanking on your neck.", "Throwing yourself up with the arms."],
 ["Zablokuj stopy i przyciągnij brodę.",
  "Podnoś się, rolując kręgosłup po kolei.",
  "Opuszczaj powoli."],
 ["Szarpanie za szyję.", "Wyrzucanie się rękami."])

add("Mountain_Climbers",
 ["Hands under your shoulders, body in a straight line.",
  "Drive your knees toward your chest one at a time.",
  "Keep your hips level."],
 ["Hips bouncing up and down.", "Shoulders drifting behind your hands."],
 ["Dłonie pod barkami, ciało w jednej linii.",
  "Przyciągaj kolana do klatki na zmianę.",
  "Biodra na stałej wysokości."],
 ["Biodra podskakujące w górę i w dół.", "Barki cofające się za dłonie."])

add("Farmers_Walk nt_suitcase_carry",
 ["Stand tall with your shoulders pulled down.",
  "Take short, quick steps.",
  "Brace so your torso doesn't lean or sway."],
 ["Leaning toward the weight.", "Letting the shoulders shrug up."],
 ["Stań prosto, barki ściągnięte w dół.",
  "Rób krótkie, szybkie kroki.",
  "Napnij brzuch, żeby tułów się nie przechylał ani nie kołysał."],
 ["Przechylanie się w stronę ciężaru.", "Unoszenie barków."])

add("Palms-Up_Barbell_Wrist_Curl_Over_A_Bench Palms-Up_Dumbbell_Wrist_Curl_Over_A_Bench Seated_Dumbbell_Palms-Up_Wrist_Curl "
    "Seated_One-Arm_Dumbbell_Palms-Up_Wrist_Curl Seated_Palm-Up_Barbell_Wrist_Curl Seated_Two-Arm_Palms-Up_Low-Pulley_Wrist_Curl Cable_Wrist_Curl",
 ["Rest your forearms so only your hands hang off.",
  "Let the weight roll down toward your fingertips.",
  "Curl your wrists up as high as you can."],
 ["Lifting the forearms off the support.", "Going too heavy to use the full range."],
 ["Oprzyj przedramiona tak, żeby wystawały tylko dłonie.",
  "Pozwól ciężarowi stoczyć się do palców.",
  "Uginaj nadgarstki jak najwyżej."],
 ["Odrywanie przedramion od podparcia.", "Zbyt duży ciężar, przez który skraca się ruch."])

add("Palms-Down_Wrist_Curl_Over_A_Bench Palms-Down_Dumbbell_Wrist_Curl_Over_A_Bench Seated_Dumbbell_Palms-Down_Wrist_Curl "
    "Seated_One-Arm_Dumbbell_Palms-Down_Wrist_Curl Seated_Palms-Down_Barbell_Wrist_Curl",
 ["Rest your forearms with your palms facing down.",
  "Lift the backs of your hands up as far as they go.",
  "Use a light weight and move slowly."],
 ["Lifting the forearms off the support.", "Bouncing the weight."],
 ["Oprzyj przedramiona dłońmi w dół.",
  "Unoś grzbiety dłoni jak najwyżej.",
  "Mały ciężar i powolny ruch."],
 ["Odrywanie przedramion od podparcia.", "Odbijanie ciężaru."])

add("External_Rotation External_Rotation_with_Band External_Rotation_with_Cable",
 ["Keep your elbow bent at 90° and tucked at your side.",
  "Rotate your forearm outward without moving the elbow.",
  "Use a light weight and control the return."],
 ["Letting the elbow drift away from the body.", "Twisting the torso to rotate further."],
 ["Łokieć zgięty pod kątem 90° i przy tułowiu.",
  "Obracaj przedramię na zewnątrz bez ruszania łokciem.",
  "Mały ciężar i kontrolowany powrót."],
 ["Łokieć odsuwający się od tułowia.", "Skręcanie tułowia, żeby obrócić dalej."])

add("nt_tibialis_raise nt_hs_tibia_dorsi_flexion",
 ["Keep your heels down.",
  "Pull your toes up toward your shins as high as you can.",
  "Lower slowly."],
 ["Rocking the body to lift the toes.", "Cutting the range short."],
 ["Pięty na podłożu.",
  "Przyciągaj palce stóp do piszczeli jak najwyżej.",
  "Opuszczaj powoli."],
 ["Bujanie ciałem, żeby unieść palce.", "Skracanie zakresu ruchu."])

add("Front_Box_Jump Box_Jump_Multiple_Response",
 ["Swing your arms and jump from a quarter squat.",
  "Land softly with your knees tracking over your toes.",
  "Stand up tall on the box, then step down."],
 ["Choosing a box so high you land in a deep squat.", "Jumping back down instead of stepping."],
 ["Zamachnij się rękami i wybij z ćwierćprzysiadu.",
  "Ląduj miękko, kolana w linii palców.",
  "Wyprostuj się na skrzyni, potem zejdź."],
 ["Zbyt wysoka skrzynia, na której lądujesz w głębokim przysiadzie.", "Zeskakiwanie zamiast schodzenia."])

add("nt_hs_reverse_hyper Reverse_Hyperextension",
 ["Lie with your hips at the edge of the pad and hold the handles.",
  "Swing your legs up behind you by squeezing your glutes, until they are in line with your body.",
  "Let them swing back down under control."],
 ["Arching the lower back to lift the legs higher.", "Using a big swing so momentum does the work."],
 ["Połóż się biodrami na krawędzi podparcia i chwyć uchwyty.",
  "Unoś nogi do tyłu, napinając pośladki, aż będą w linii z tułowiem.",
  "Opuszczaj je pod kontrolą."],
 ["Wyginanie lędźwi, żeby unieść nogi wyżej.", "Duży zamach, przez który pracuje rozpęd."])


def main():
    ids = {e["id"] for e in json.loads((ROOT / "android/app/src/main/assets/exercises.json").read_text())}
    unknown = sorted(set(C) - ids)
    assert not unknown, f"not in the exercise library: {unknown}"
    text = json.dumps(C, indent=1, ensure_ascii=False, sort_keys=True) + "\n"
    for target in TARGETS:
        target.write_text(text)
        print(f"wrote {target.relative_to(ROOT)} ({len(C)} exercises)")


if __name__ == "__main__":
    main()
