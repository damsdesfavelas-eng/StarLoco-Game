<?php
// Atrion : etat public du serveur, lu par le launcher (joueurs en ligne, evenements, chasse du jour, classement).
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('Access-Control-Allow-Origin: *');

ob_start();
$out = ['ok' => false];
try {
    require_once __DIR__ . '/configuration/configuration.php';
    ob_end_clean();
    $db = $connection;
    $out['online'] = (int) $db->query('SELECT COUNT(*) FROM world_players WHERE logged = 1')->fetchColumn();
    $status = [];
    try {
        foreach ($db->query('SELECT k, v FROM atrion_status') as $row) $status[$row['k']] = $row['v'];
    } catch (Exception $e) { }
    $fresh = isset($status['updated']) && (microtime(true) * 1000 - (float) $status['updated']) < 5 * 60 * 1000;
    $out['bonus'] = $status['bonus'] ?? '';
    $out['events'] = $fresh && !empty($status['events']) ? explode("\n", $status['events']) : [];
    $out['hunt'] = $status['hunt'] ?? '';
    $top = [];
    try {
        $q = $db->query('SELECT p.name, a.points FROM atrion_points a JOIN world_players p ON p.id = a.player_id ORDER BY a.points DESC LIMIT 5');
        foreach ($q as $row) $top[] = ['name' => $row['name'], 'points' => (int) $row['points']];
    } catch (Exception $e) { }
    $out['top'] = $top;
    $out['ok'] = true;
} catch (Throwable $e) {
    if (ob_get_level()) ob_end_clean();
    $out['error'] = 'indisponible';
}
echo json_encode($out, JSON_UNESCAPED_UNICODE);
